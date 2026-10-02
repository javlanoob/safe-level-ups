package com.safelevelups;

import java.util.EnumMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.Skill;
import net.runelite.api.WidgetNode;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
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

	/**
	 * A colour tag with no text after it to colour, so the game draws nothing for it.
	 */
	private static final String DRAWN_AS_NOTHING = "<col=ffffff>";

	private static int groupFor(Skill skill)
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
	 * How long the box stays up for a click that was not aimed at it. Nothing stalls the player while
	 * it is open, so a click they had already lined up for something else would otherwise dismiss it
	 * before they read it. The click still counts, it just takes effect once this has passed.
	 * <p>
	 * Pressing the box's own Continue is not that kind of click and does not wait.
	 */
	private static final long MINIMUM_OPEN_MILLIS = 600L;

	/**
	 * How long to wait for an interface that never builds before closing it anyway. Only reached if
	 * something went wrong, because the build normally lands on the tick after opening.
	 */
	private static final long NEVER_BUILT_MILLIS = 3000L;

	private final Client client;

	/**
	 * The skill the box on screen is announcing, or null while there is no box.
	 */
	private Skill skill;

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

	/**
	 * Whether the close was asked for by the box's own Continue rather than by a click that happened
	 * to land while it was up. The minimum is there for the second kind.
	 */
	private volatile boolean closeNow;

	LevelUpInterface(Client client)
	{
		this.client = client;
	}

	boolean isOpen()
	{
		return opening || node != null;
	}

	/**
	 * Whether the box on screen is the one this skill would get, so a further level in it can be
	 * shown where it stands instead of waiting behind the level it follows.
	 */
	boolean isShowing(Skill skill)
	{
		return this.skill == skill && isOpen();
	}

	/**
	 * Moves the box on screen on to a further level in the same skill. The minimum time starts again
	 * from here, because the player is being handed a line they have not read yet.
	 */
	void showLevel(int level)
	{
		this.level = level;
		openedAt = System.currentTimeMillis();
		shownAt = 0;

		// A Continue pressed for the level this one replaces was pressed for a line the player has
		// now not read, so it does not carry over and take the new one straight back off again
		closeNow = false;

		populate();
		rebuild = true;
	}

	/**
	 * Shows the box, or says that it could not be shown. The client keeps one copy of an interface,
	 * so asking for this one while the game already has it open somewhere else is refused, which is
	 * what happens when a level arrives with the game's own pop-ups still turned on. The message
	 * layer is open by that point, and an empty message layer is a blank box sitting over the chat
	 * with nothing in it to dismiss it, so a refusal puts the chatbox back before returning.
	 */
	boolean open(Skill skill, int level)
	{
		this.skill = skill;
		this.name = skill.getName();
		this.group = groupFor(skill);
		this.level = level;
		closeRequested = false;
		closeNow = false;
		openedAt = System.currentTimeMillis();
		shownAt = 0;

		// An interface already on the message layer has to go, or it keeps drawing under ours
		client.runScript(ScriptID.MESSAGE_LAYER_CLOSE, 0, 1, 0);
		client.runScript(ScriptID.MESSAGE_LAYER_OPEN, 0);

		// Opening fires a widget loaded event, so claim the interface as ours before it does
		WidgetNode opened = null;
		opening = true;
		try
		{
			opened = client.openInterface(InterfaceID.Chatbox.MES_LAYER, InterfaceID.LEVELUP_DISPLAY,
				WidgetModalMode.MODAL_CLICKTHROUGH);
		}
		catch (RuntimeException refused)
		{
			// Left to itself this would also leave the box counted as open, and a box counted as open
			// is never opened again and never closed either
		}
		finally
		{
			opening = false;
		}

		node = opened;

		if (opened == null)
		{
			skill = null;
			restore();
			return false;
		}

		// The interface may not be built yet, so fill it in again on the next tick
		populate();
		rebuild = true;
		return true;
	}

	/**
	 * The game closing the chatbox for something of its own, which takes this box down with it. The
	 * interface has already gone by the time this runs, so it is let go of rather than closed again,
	 * and what is left behind is handed back the same way closing by hand hands it back.
	 */
	void onMessageLayerClosed()
	{
		if (node == null)
		{
			return;
		}

		node = null;
		skill = null;
		rebuild = false;
		closeRequested = false;
		closeNow = false;

		unhide(InterfaceID.Chatbox.MES_TEXT);
		unhide(InterfaceID.Chatbox.MES_TEXT2);
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

		// Aimed at the box, so there is nothing to protect the player from
		if (closeNow)
		{
			close();
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

	/**
	 * The player pressing Continue on the box itself, which comes down at once. Anything waiting
	 * behind it is up on the same frame, so a quest handing out a pile of levels is clicked through
	 * at the speed of the clicking rather than a tick at a time.
	 */
	void requestCloseNow()
	{
		closeNow = true;
		closeRequested = true;
	}

	void close()
	{
		WidgetNode open = node;
		node = null;
		opening = false;
		skill = null;
		rebuild = false;
		closeRequested = false;
		closeNow = false;

		if (open == null)
		{
			return;
		}

		try
		{
			client.closeInterface(open, true);
		}
		catch (IllegalArgumentException alreadyGone)
		{
			// Logging out unlinks the interface before we get here, and closing it then is an error
		}

		// Whether or not there was still an interface there to close, the chatbox goes back to the
		// state it was taken from. Leaving this to the successful case is what turns a box the game
		// pulled out from under us into a blank one that stays on screen
		restore();
	}

	/**
	 * Puts the chatbox back the way it was found.
	 * <p>
	 * The two prompt lines were hidden so they could not draw over the box. They belong to the game,
	 * not to us, so they are handed back before the layer closes rather than left for the layer to
	 * restore. A prompt written into a component we had hidden and never gave back would be a prompt
	 * the player never sees.
	 */
	private void restore()
	{
		unhide(InterfaceID.Chatbox.MES_TEXT);
		unhide(InterfaceID.Chatbox.MES_TEXT2);

		client.runScript(ScriptID.MESSAGE_LAYER_CLOSE, 0, 1, 0);
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
		setText(InterfaceID.LevelupDisplay.TEXT2, line2(name, level) + screenshotGuard());

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
			continueText.setOnOpListener((JavaScriptCallback) ev -> requestCloseNow());
			continueText.setHasListener(true);
			continueText.revalidate();
		}
	}

	/**
	 * RuneLite screenshots a level up twice when the box comes from here. It takes one from the game's
	 * own level up message in the chat, which is where the level arrives once the game's pop-ups are
	 * turned off, and another from the level up interface being loaded, which is how this box is shown.
	 * Both are the same level, and the second is named from the line this goes on the end of, so the
	 * line is written with a tag that draws as nothing. It reads the same on screen and no longer reads
	 * as a level up to anything matching the game's wording.
	 * <p>
	 * Only while the game announces levels in the chat, because that is the announcement the screenshot
	 * was already taken from. With that turned off this box is the only announcement there is, and a
	 * picture of it is the picture the player wanted.
	 */
	private String screenshotGuard()
	{
		return client.getVarbitValue(VarbitID.OPTION_LEVEL_UP_MESSAGE_DISABLED) == 1 ? DRAWN_AS_NOTHING : "";
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
