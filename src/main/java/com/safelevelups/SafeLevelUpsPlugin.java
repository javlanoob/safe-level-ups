package com.safelevelups;

import java.awt.event.KeyEvent;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.GameState;
import net.runelite.api.ScriptID;
import net.runelite.api.Skill;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.vars.InputType;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@PluginDescriptor(
	name = "Safe Level Ups",
	description = "Shows the level up box in the chatbox without the game interrupting you",
	tags = {"level", "skill", "experience", "hardcore", "ironman"}
)
public class SafeLevelUpsPlugin extends Plugin implements KeyListener
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private KeyManager keyManager;

	/**
	 * A 99 also brings a second pop-up on another interface, pointing at the skillcape seller. Its
	 * wording is written per skill and only the server knows it, so that one is left to the game.
	 */
	private static final int MAX_ANNOUNCED_LEVEL = Experience.MAX_REAL_LEVEL;

	private static final Skill[] SKILLS = Skill.values();

	/**
	 * No experience remembered for a skill yet, so the next update is a starting point, not a level up.
	 */
	private static final int UNKNOWN = -1;

	private final int[] lastExperience = forgottenExperience();
	private final Deque<LevelUp> pending = new ArrayDeque<>();
	private LevelUpInterface levelUpInterface;

	@Override
	protected void startUp()
	{
		levelUpInterface = new LevelUpInterface(client);
		keyManager.registerKeyListener(this);
		clientThread.invoke(this::rememberExperience);
	}

	@Override
	protected void shutDown()
	{
		keyManager.unregisterKeyListener(this);
		clientThread.invoke(levelUpInterface::close);
		forget();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();

		// A loading screen is a new region, not a new player. Forgetting here would swallow the next
		// level up, because the first experience update after it would look like a starting point
		if (state == GameState.LOGGED_IN || state == GameState.LOADING)
		{
			return;
		}

		// Experience is only known once logged in, and belongs to whoever logs in next
		levelUpInterface.close();
		forget();
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		int experience = event.getXp();
		int index = skill.ordinal();
		int previous = lastExperience[index];
		lastExperience[index] = experience;

		// The first update after logging in is the starting point, not a level up
		if (previous == UNKNOWN || experience <= previous)
		{
			return;
		}

		int level = Experience.getLevelForXp(experience);
		if (level > Experience.getLevelForXp(previous) && level <= MAX_ANNOUNCED_LEVEL)
		{
			announce(skill, level);
		}
	}

	/**
	 * A second level in a skill whose box is already on screen, or already waiting its turn, is the
	 * same news one level further on, so it takes the place of the level it follows rather than
	 * queueing behind it. Levelling twice in one action, which a full inventory of potions does, then
	 * reads as the level the player ended on instead of making them dismiss a level they are already
	 * past. Another skill is news of its own, so it still waits for the box in front of it.
	 */
	private void announce(Skill skill, int level)
	{
		if (levelUpInterface.isShowing(skill))
		{
			levelUpInterface.showLevel(level);
			return;
		}

		for (LevelUp queued : pending)
		{
			if (queued.skill == skill)
			{
				queued.level = level;
				return;
			}
		}

		pending.add(new LevelUp(skill, level));
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		// The game showed its own level up interface, so ours would only repeat it
		if (event.getGroupId() == InterfaceID.LEVELUP_DISPLAY && !levelUpInterface.isOpen())
		{
			pending.clear();
		}
	}

	/**
	 * The game closing the chatbox for something of its own, which takes the box with it. Noticing it
	 * here is what keeps a box that has already gone from being counted as still on screen.
	 */
	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (event.getScriptId() == ScriptID.MESSAGE_LAYER_CLOSE)
		{
			levelUpInterface.onMessageLayerClosed();
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		// Whatever the click was for, it goes through to the game. The box just gets out of the way
		if (levelUpInterface.isOpen())
		{
			levelUpInterface.requestClose();
		}
	}

	@Override
	public void keyTyped(KeyEvent event)
	{
		if (event.getKeyChar() != ' ' || !levelUpInterface.isOpen())
		{
			return;
		}

		// A space belongs to the chatbox if the player is part way through typing something
		String typed = client.getVarcStrValue(VarClientID.CHATINPUT);
		if (typed != null && !typed.isEmpty())
		{
			return;
		}

		levelUpInterface.requestClose();
		event.consume();
	}

	@Override
	public void keyPressed(KeyEvent event)
	{
	}

	@Override
	public void keyReleased(KeyEvent event)
	{
	}

	/**
	 * A click asking the box to close is honoured here rather than on the game tick, so the time it
	 * stays up is the time the player actually sees rather than that plus up to a tick.
	 */
	@Subscribe
	public void onClientTick(ClientTick event)
	{
		levelUpInterface.onClientTick();
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		levelUpInterface.onGameTick();

		if (pending.isEmpty() || levelUpInterface.isOpen())
		{
			return;
		}

		// Wait for the chatbox to be free, so this never replaces a dialogue the player is reading
		Widget chatbox = client.getWidget(InterfaceID.Chatbox.MES_LAYER);
		if (chatbox == null || !chatbox.isHidden())
		{
			return;
		}

		// A prompt such as "Enter amount:" or a bank search draws on the chatbox's own text
		// components, which sit beside the message layer rather than inside it, so the layer still
		// reads as hidden while they are on screen. The input type is what says the chatbox is busy,
		// and it covers panels other plugins put there as well as the game's own prompts
		if (client.getVarcIntValue(VarClientID.MESLAYERMODE) != InputType.NONE.getType())
		{
			return;
		}

		LevelUp levelUp = pending.remove();

		// A box the client would not show is a box the game is announcing itself, so the levels
		// behind it are its news to give rather than a queue to work through once it is done
		if (!levelUpInterface.open(levelUp.skill, levelUp.level))
		{
			pending.clear();
		}
	}

	private void rememberExperience()
	{
		forget();
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		for (Skill skill : SKILLS)
		{
			lastExperience[skill.ordinal()] = client.getSkillExperience(skill);
		}
	}

	private void forget()
	{
		Arrays.fill(lastExperience, UNKNOWN);
		pending.clear();
	}

	/**
	 * A zeroed table would read as a real skill sitting at no experience, so it starts out unknown.
	 */
	private static int[] forgottenExperience()
	{
		int[] table = new int[SKILLS.length];
		Arrays.fill(table, UNKNOWN);
		return table;
	}

	private static class LevelUp
	{
		private final Skill skill;

		/**
		 * Not final: a further level in the same skill overwrites the one waiting rather than adding
		 * another box behind it.
		 */
		private int level;

		private LevelUp(Skill skill, int level)
		{
			this.skill = skill;
			this.level = level;
		}
	}
}
