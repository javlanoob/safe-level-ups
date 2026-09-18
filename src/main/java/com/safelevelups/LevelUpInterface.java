package com.safelevelups;

import java.util.EnumMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.Skill;
import net.runelite.api.WidgetNode;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetModalMode;

/**
 * The game's own level up interface, opened by the client instead of the server.
 * <p>
 * The models, fonts and layout all come from the game's interface definition, so this stays identical
 * to a real level up even when Jagex changes it. Only the parts the server would fill in are set here.
 */
class LevelUpInterface
{
	/**
	 * The group inside the interface holding each skill's models. Exactly one is shown at a time.
	 */
	private static final Map<Skill, Integer> SKILL_GROUPS = new EnumMap<>(Skill.class);

	/**
	 * Every group the interface has. The combat one belongs to no skill, and is only here so that it
	 * is hidden along with the rest rather than left showing next to the skill that is being announced.
	 */
	private static final int[] ALL_GROUPS;

	static
	{
		SKILL_GROUPS.put(Skill.ATTACK, InterfaceID.LevelupDisplay.ATTACK);
		SKILL_GROUPS.put(Skill.STRENGTH, InterfaceID.LevelupDisplay.STRENGTH);
		SKILL_GROUPS.put(Skill.DEFENCE, InterfaceID.LevelupDisplay.DEFENCE);
		SKILL_GROUPS.put(Skill.RANGED, InterfaceID.LevelupDisplay.RANGED);
		SKILL_GROUPS.put(Skill.PRAYER, InterfaceID.LevelupDisplay.PRAYER);
		SKILL_GROUPS.put(Skill.MAGIC, InterfaceID.LevelupDisplay.MAGIC);
		SKILL_GROUPS.put(Skill.HITPOINTS, InterfaceID.LevelupDisplay.HITPOINTS);
		SKILL_GROUPS.put(Skill.AGILITY, InterfaceID.LevelupDisplay.AGILITY);
		SKILL_GROUPS.put(Skill.HERBLORE, InterfaceID.LevelupDisplay.HERBLORE);
		SKILL_GROUPS.put(Skill.THIEVING, InterfaceID.LevelupDisplay.THIEVING);
		SKILL_GROUPS.put(Skill.CRAFTING, InterfaceID.LevelupDisplay.CRAFTING);
		SKILL_GROUPS.put(Skill.FLETCHING, InterfaceID.LevelupDisplay.FLETCHING);
		SKILL_GROUPS.put(Skill.MINING, InterfaceID.LevelupDisplay.MINING);
		SKILL_GROUPS.put(Skill.SMITHING, InterfaceID.LevelupDisplay.SMITHING);
		SKILL_GROUPS.put(Skill.FISHING, InterfaceID.LevelupDisplay.FISHING);
		SKILL_GROUPS.put(Skill.COOKING, InterfaceID.LevelupDisplay.COOKING);
		SKILL_GROUPS.put(Skill.FIREMAKING, InterfaceID.LevelupDisplay.FIREMAKING);
		SKILL_GROUPS.put(Skill.WOODCUTTING, InterfaceID.LevelupDisplay.WOODCUTTING);
		SKILL_GROUPS.put(Skill.RUNECRAFT, InterfaceID.LevelupDisplay.RUNECRAFT);
		SKILL_GROUPS.put(Skill.SLAYER, InterfaceID.LevelupDisplay.SLAYER);
		SKILL_GROUPS.put(Skill.FARMING, InterfaceID.LevelupDisplay.FARMING);
		SKILL_GROUPS.put(Skill.HUNTER, InterfaceID.LevelupDisplay.HUNTER);
		SKILL_GROUPS.put(Skill.CONSTRUCTION, InterfaceID.LevelupDisplay.CONSTRUCTION);
		SKILL_GROUPS.put(Skill.SAILING, InterfaceID.LevelupDisplay.SAILING);

		ALL_GROUPS = new int[SKILL_GROUPS.size() + 1];
		int index = 0;
		for (int group : SKILL_GROUPS.values())
		{
			ALL_GROUPS[index++] = group;
		}
		ALL_GROUPS[index] = InterfaceID.LevelupDisplay.COMBAT;
	}

	private static final String CONTINUE_TEXT = "Click here to continue";

	static int groupFor(Skill skill)
	{
		return SKILL_GROUPS.getOrDefault(skill, -1);
	}

	/**
	 * The first line of the box, as the server would word it.
	 */
	private static String line1(String name)
	{
		// "you just", not "you've just". Taken from a screenshot of the game rather than from a
		// newspost quoting the line, because the newspost tidied it up and the game did not
		return "Congratulations, you just advanced " + article(name) + name + " level.";
	}

	/**
	 * The second line of the box. Hitpoints is the one skill the game words in the plural.
	 */
	private static String line2(String name, int level)
	{
		return ("Hitpoints".equals(name)
			? "Your Hitpoints are now "
			: "Your " + name + " level is now ") + level + '.';
	}

	/**
	 * How long the box stays up whatever the player does. Nothing stalls them while it is open, so a
	 * click they had already lined up for something else would otherwise dismiss it before they read
	 * it. The click still counts, it just takes effect once this has passed.
	 */
	private static final long MINIMUM_OPEN_MILLIS = 600L;

	/**
	 * How long to wait for an interface that never builds before closing it anyway. Only reached if
	 * something went wrong, because the build normally lands on the tick after opening.
	 */
	private static final long NEVER_BUILT_MILLIS = 3000L;

	private final Client client;
	private String name;
	private int group;
	private int level;
	private boolean rebuild;
	private long openedAt;

	/**
	 * When the box was first confirmed on screen, or 0 while it is still only asked for. The minimum
	 * is counted from here rather than from the request, because an interface is not built on the
	 * tick it is opened. Counting from the request spends most of the second on a box nobody can see.
	 */
	private long shownAt;

	/**
	 * Read from the keyboard thread as well as the client thread.
	 */
	private volatile WidgetNode node;
	private volatile boolean opening;
	private volatile boolean closeRequested;

	LevelUpInterface(Client client)
	{
		this.client = client;
	}

	boolean isOpen()
	{
		return opening || node != null;
	}

	void open(String name, int group, int level)
	{
		this.name = name;
		this.group = group;
		this.level = level;
		closeRequested = false;
		openedAt = System.currentTimeMillis();
		shownAt = 0;

		// An interface already on the message layer has to go, or it keeps drawing under ours
		client.runScript(ScriptID.MESSAGE_LAYER_CLOSE, 0, 1, 0);
		client.runScript(ScriptID.MESSAGE_LAYER_OPEN, 0);

		// Opening fires a widget loaded event, so claim the interface as ours before it does
		opening = true;
		node = client.openInterface(InterfaceID.Chatbox.MES_LAYER, InterfaceID.LEVELUP_DISPLAY,
			WidgetModalMode.MODAL_CLICKTHROUGH);
		opening = false;

		// The interface may not be built yet, so fill it in again on the next tick
		populate();
		rebuild = true;
	}

	void onGameTick()
	{
		if (rebuild)
		{
			rebuild = false;
			populate();
		}
	}

	/**
	 * Closing is checked every frame rather than every game tick, because a tick is 600ms and the box
	 * is meant to come down half a second after the player asks for it, not up to a tick later.
	 */
	void onClientTick()
	{
		if (!closeRequested)
		{
			return;
		}

		long now = System.currentTimeMillis();

		if (shownAt == 0)
		{
			// Asked for but not drawn yet. Closing on the clock from the request would take the box
			// away before it ever appeared, which is the whole thing the minimum exists to prevent
			if (now - openedAt >= NEVER_BUILT_MILLIS)
			{
				close();
			}
			return;
		}

		if (now - shownAt >= MINIMUM_OPEN_MILLIS)
		{
			close();
		}
	}

	void requestClose()
	{
		closeRequested = true;
	}

	void close()
	{
		WidgetNode open = node;
		node = null;
		rebuild = false;
		closeRequested = false;

		if (open == null)
		{
			return;
		}

		try
		{
			client.closeInterface(open, true);

			// The two prompt lines were hidden so they could not draw over the box. They belong to
			// the game, not to us, so they are handed back before the layer closes rather than left
			// for the layer to restore. A prompt written into a component we had hidden and never
			// gave back would be a prompt the player never sees
			unhide(InterfaceID.Chatbox.MES_TEXT);
			unhide(InterfaceID.Chatbox.MES_TEXT2);

			client.runScript(ScriptID.MESSAGE_LAYER_CLOSE, 0, 1, 0);
		}
		catch (IllegalArgumentException alreadyGone)
		{
			// Logging out unlinks the interface before we get here, and closing it then is an error
		}
	}

	/**
	 * Fills in what the server would normally send: the two lines of text, the continue prompt,
	 * and which skill's models to show.
	 */
	private void populate()
	{
		// Opening the message layer puts the chatbox's own two prompt lines back on screen still
		// holding whatever was last written to them, such as a bank search or an "Enter amount:",
		// and they draw over our box. They sit beside the layer rather than inside it, so closing
		// the layer does not clear them. RuneLite hides these same two components whenever it takes
		// the chatbox for a panel of its own, and the message layer closing puts them back
		hide(InterfaceID.Chatbox.MES_TEXT);
		hide(InterfaceID.Chatbox.MES_TEXT2);

		Widget first = setText(InterfaceID.LevelupDisplay.TEXT1, line1(name));
		setText(InterfaceID.LevelupDisplay.TEXT2, line2(name, level));

		// The interface exists from this point, so this is the first moment the box could be drawn
		if (first != null && shownAt == 0)
		{
			shownAt = System.currentTimeMillis();
		}

		for (int candidate : ALL_GROUPS)
		{
			Widget models = client.getWidget(candidate);
			if (models == null)
			{
				continue;
			}

			models.setHidden(candidate != group);
			models.revalidate();
		}

		// The real interface asks the server to close itself, which is the part we do not want
		Widget continueText = setText(InterfaceID.LevelupDisplay.CONTINUE, CONTINUE_TEXT);
		if (continueText != null)
		{
			continueText.setAction(0, "Continue");
			continueText.setOnOpListener((JavaScriptCallback) ev -> requestClose());
			continueText.setHasListener(true);
			continueText.revalidate();
		}
	}

	/**
	 * Attack and Agility are the only names the game puts "an" in front of, and both start with a vowel.
	 */
	private static String article(String name)
	{
		return "AEIOU".indexOf(name.charAt(0)) >= 0 ? "an " : "a ";
	}

	private void hide(int componentId)
	{
		Widget widget = client.getWidget(componentId);
		if (widget != null && !widget.isHidden())
		{
			widget.setHidden(true);
		}
	}

	private void unhide(int componentId)
	{
		Widget widget = client.getWidget(componentId);
		if (widget != null && widget.isHidden())
		{
			widget.setHidden(false);
		}
	}

	private Widget setText(int componentId, String text)
	{
		Widget widget = client.getWidget(componentId);
		if (widget != null)
		{
			widget.setText(text);
			widget.setHidden(false);
			widget.revalidate();
		}
		return widget;
	}
}
