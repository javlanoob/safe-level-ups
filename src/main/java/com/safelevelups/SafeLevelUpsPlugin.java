package com.safelevelups;

import java.awt.event.KeyEvent;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
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
			pending.add(new LevelUp(skill.getName(), LevelUpInterface.groupFor(skill), level));
		}
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
		levelUpInterface.open(levelUp.name, levelUp.group, levelUp.level);
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
		private final String name;
		private final int group;
		private final int level;

		private LevelUp(String name, int group, int level)
		{
			this.name = name;
			this.group = group;
			this.level = level;
		}
	}
}
