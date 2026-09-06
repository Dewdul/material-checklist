package com.materialchecklist;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.KeyCode;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.Skill;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.JagexColors;
import net.runelite.client.util.Text;

/**
 * In-game entry points for adding finished goods to the checklist.
 *
 * Skill guides get a real right-click menu entry. Most skill-guide entries
 * have no vanilla menu ops (only Magic/Construction/Sailing do), so
 * MenuEntryAdded never identifies the hovered row — instead we hook
 * MenuOpened and hit-test the mouse against the guide's row widgets. Both
 * guide versions are handled: the legacy parchment guide (group 214) and the
 * modern one (group 860, four dynamic children per row). Entries use
 * MenuAction.RUNELITE with an onClick consumer, so clicking runs purely
 * client-side and nothing is sent to the server.
 *
 * The POH furniture and ship customisation menus must NOT get injected menu
 * entries (hub rule) — those are covered by passively observing shift-clicks.
 */
@Slf4j
@Singleton
public class GameMenuSupport
{
	private static final String ADD_OPTION = "Add to Checklist";

	private final Client client;
	private final ItemManager itemManager;
	private final RecipeBook recipeBook;
	private final MaterialChecklistConfig config;

	/** Set by the plugin at startUp; receives the recipe chosen in-game. */
	private Consumer<Recipe> onAdd;

	private List<Integer> buildInterfaces;

	@Inject
	GameMenuSupport(Client client, ItemManager itemManager, RecipeBook recipeBook, MaterialChecklistConfig config)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.recipeBook = recipeBook;
		this.config = config;
	}

	void init(Consumer<Recipe> onAdd)
	{
		this.onAdd = onAdd;
		buildInterfaces = Arrays.asList(
			InterfaceID.POH_FURNITURE_CREATION,
			InterfaceID.POH_FURNITURE_CREATION_MENU,
			InterfaceID.SAILING_CUSTOMISATION,
			InterfaceID.SAILING_BOAT_SELECTION,
			InterfaceID.SAILING_BT_SELECTION,
			InterfaceID.SAILING_MENU
		);
	}

	/** Client thread. */
	void onMenuOpened(MenuOpened event)
	{
		if (!config.skillGuideMenu() || onAdd == null)
		{
			return;
		}
		Point mouse = client.getMouseCanvasPosition();

		// Modern skill guide (group 860): rows are 4 dynamic children on LIST:
		// [row rect, level text, item graphic, description text]. Rows scrolled
		// out of the viewport keep canvas bounds outside the list, so gate the
		// whole test on the mouse being inside the list container first.
		Widget list = client.getWidget(InterfaceID.SkillGuideV2.LIST);
		if (list != null && !list.isHidden() && containsOrDegenerate(list, mouse))
		{
			Widget[] children = list.getDynamicChildren();
			for (int i = 0; i + 3 < children.length; i += 4)
			{
				if (children[i + 2] != null && hit(children[i], mouse))
				{
					offerEntry(children[i + 2], InterfaceID.SKILL_GUIDE_V2);
					return;
				}
			}
			log.debug("skill guide v2: no row hit at {},{} ({} children)",
				mouse.getX(), mouse.getY(), children.length);
		}

		// Legacy skill guide (group 214): icon child i on ICONS, text children
		// 2i (level) / 2i+1 (description) on INFO
		Widget icons = client.getWidget(InterfaceID.SkillGuide.ICONS);
		Widget info = client.getWidget(InterfaceID.SkillGuide.INFO);
		if (icons != null && !icons.isHidden() && info != null)
		{
			if (!containsOrDegenerate(icons, mouse) && !containsOrDegenerate(info, mouse))
			{
				log.debug("legacy skill guide: gate rejected mouse {},{} (icons {} info {})",
					mouse.getX(), mouse.getY(), icons.getBounds(), info.getBounds());
				return;
			}
			Widget[] iconChildren = icons.getDynamicChildren();
			for (int i = 0; i < iconChildren.length; i++)
			{
				if (iconChildren[i] == null)
				{
					continue;
				}
				if (hit(iconChildren[i], mouse)
					|| hit(info.getChild(i * 2), mouse)
					|| hit(info.getChild(i * 2 + 1), mouse))
				{
					offerEntry(iconChildren[i], InterfaceID.SKILL_GUIDE);
					return;
				}
			}
			log.debug("legacy skill guide: no row hit at {},{} ({} icon children)",
				mouse.getX(), mouse.getY(), iconChildren.length);
		}
	}

	/** True when the mouse is inside the widget's bounds. */
	private static boolean hit(Widget widget, Point mouse)
	{
		return widget != null && widget.getBounds().contains(mouse.getX(), mouse.getY());
	}

	/**
	 * Container gate for scroll clipping. Some layer widgets report zero-size
	 * bounds even though their children render — a degenerate container must
	 * not veto the row hit-test, so it passes.
	 */
	private static boolean containsOrDegenerate(Widget container, Point mouse)
	{
		Rectangle bounds = container.getBounds();
		if (bounds == null || bounds.width <= 0 || bounds.height <= 0)
		{
			return true;
		}
		return bounds.contains(mouse.getX(), mouse.getY());
	}

	private void offerEntry(Widget iconWidget, int guideInterfaceId)
	{
		int itemId = iconWidget.getItemId();
		// -1 = sprite-override rows; BLANKOBJECT = invisible dummy rows
		if (itemId <= 0 || itemId == ItemID.BLANKOBJECT)
		{
			log.debug("skill guide row has no usable item id ({})", itemId);
			return;
		}
		int canonical = itemManager.canonicalize(itemId);
		String name = itemManager.getItemComposition(canonical).getMembersName();
		List<Recipe> candidates = candidatesWithChain(canonical, name);
		if (candidates.isEmpty())
		{
			log.debug("no recipe for guide entry '{}' (item {} canonical {})", name, itemId, canonical);
			return;
		}
		// The guide's skill is the strongest hint of intent: browsing the
		// Farming guide and clicking Ranarr means seeds, not herb cleaning.
		String guideSkill = detectGuideSkill(guideInterfaceId);
		if (guideSkill != null)
		{
			List<Recipe> matching = new ArrayList<>();
			for (Recipe candidate : candidates)
			{
				if (guideSkill.equalsIgnoreCase(candidate.skill))
				{
					matching.add(candidate);
				}
			}
			if (!matching.isEmpty())
			{
				candidates = matching;
			}
		}
		attachAddEntry(canonical, JagexColors.MENU_TARGET_TAG + name, candidates);
	}

	/**
	 * Direct recipes for the item, plus one step back down the chain: for a
	 * single-ingredient recipe (herb cleaning, log cutting...) the recipes
	 * producing that ingredient are offered too, so a clean herb can resolve
	 * to growing the grimy one from seed.
	 */
	private List<Recipe> candidatesWithChain(int canonical, String name)
	{
		List<Recipe> direct = recipeBook.candidatesFor(canonical, name);
		Map<String, Recipe> union = new LinkedHashMap<>();
		for (Recipe recipe : direct)
		{
			union.put(recipe.name.toLowerCase(), recipe);
		}
		for (Recipe recipe : direct)
		{
			if (recipe.ingredients().size() == 1)
			{
				for (Recipe producer : recipeBook.producersOf(recipe.ingredients().get(0).itemId))
				{
					union.putIfAbsent(producer.name.toLowerCase(), producer);
				}
			}
		}
		List<Recipe> candidates = new ArrayList<>(union.values());
		candidates.sort((a, b) ->
		{
			int cmp = Integer.compare(a.level, b.level);
			return cmp != 0 ? cmp : a.name.compareToIgnoreCase(b.name);
		});
		return candidates.size() > 10 ? candidates.subList(0, 10) : candidates;
	}

	/**
	 * Reads the open guide's skill from its title text (e.g. "Sailing" or
	 * "Farming Guide"). Null when no title matches a skill name — candidates
	 * are then left unfiltered.
	 */
	private String detectGuideSkill(int guideInterfaceId)
	{
		for (int child = 0; child < 60; child++)
		{
			Widget widget = client.getWidget(WidgetUtil.packComponentId(guideInterfaceId, child));
			if (widget == null)
			{
				continue;
			}
			String skill = skillNameIn(widget.getText());
			if (skill != null)
			{
				return skill;
			}
			Widget[] dynamicChildren = widget.getDynamicChildren();
			if (dynamicChildren != null)
			{
				for (Widget kid : dynamicChildren)
				{
					skill = kid == null ? null : skillNameIn(kid.getText());
					if (skill != null)
					{
						return skill;
					}
				}
			}
			Widget[] staticChildren = widget.getStaticChildren();
			if (staticChildren != null)
			{
				for (Widget kid : staticChildren)
				{
					skill = kid == null ? null : skillNameIn(kid.getText());
					if (skill != null)
					{
						return skill;
					}
				}
			}
		}
		return null;
	}

	private static String skillNameIn(String text)
	{
		if (text == null || text.isEmpty())
		{
			return null;
		}
		String cleaned = Text.removeTags(text).trim();
		for (Skill skill : Skill.values())
		{
			if (cleaned.equalsIgnoreCase(skill.getName())
				|| cleaned.equalsIgnoreCase(skill.getName() + " Guide"))
			{
				return skill.getName();
			}
		}
		return null;
	}

	/**
	 * Creates the "Add to Checklist" entry. A single production method adds
	 * directly; multiple methods expand into a submenu so the user picks the
	 * variant (e.g. Mithril keel (Skiff) vs (Sloop)) before anything is added.
	 */
	private void attachAddEntry(int canonicalId, String target, List<Recipe> candidates)
	{
		MenuEntry parent = client.getMenu().createMenuEntry(-1)
			.setOption(ADD_OPTION)
			.setTarget(target)
			.setType(MenuAction.RUNELITE)
			.setItemId(canonicalId);
		if (candidates.size() == 1)
		{
			Recipe only = candidates.get(0);
			parent.onClick(e -> onAdd.accept(only));
			return;
		}
		Menu subMenu = parent.createSubMenu();
		for (Recipe variant : candidates)
		{
			subMenu.createMenuEntry(0)
				.setOption("Add")
				.setTarget(JagexColors.MENU_TARGET_TAG + variant.name)
				.setType(MenuAction.RUNELITE)
				.onClick(e -> onAdd.accept(variant));
		}
	}

	/** Client thread; fires per entry per frame — keep cheap. */
	void onMenuEntryAdded(MenuEntryAdded event)
	{
		MaterialChecklistConfig.AddMenuMode mode = config.itemMenuMode();
		if (mode == MaterialChecklistConfig.AddMenuMode.DISABLED
			|| (mode == MaterialChecklistConfig.AddMenuMode.SHIFT && !client.isKeyPressed(KeyCode.KC_SHIFT))
			|| onAdd == null || !"Examine".equals(event.getOption()))
		{
			return;
		}
		int component = event.getActionParam1();
		if (component != InterfaceID.Bankmain.ITEMS && component != InterfaceID.Inventory.ITEMS)
		{
			return;
		}
		int itemId = event.getItemId();
		if (itemId <= 0)
		{
			return;
		}
		int canonical = itemManager.canonicalize(itemId);
		List<Recipe> candidates = candidatesWithChain(canonical, null);
		if (candidates.isEmpty())
		{
			return;
		}
		attachAddEntry(canonical, event.getTarget(), candidates);
	}

	/** Client thread. Passive capture in POH furniture / ship customisation menus. */
	void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!config.buildMenuAdd() || onAdd == null
			|| (config.buildMenuRequireShift() && !client.isKeyPressed(KeyCode.KC_SHIFT)))
		{
			return;
		}
		int interfaceId = WidgetUtil.componentToInterface(event.getParam1());
		if (!buildInterfaces.contains(interfaceId))
		{
			return;
		}
		int itemId = event.getMenuEntry().getItemId();
		if (itemId <= 0)
		{
			Widget widget = event.getMenuEntry().getWidget();
			if (widget != null)
			{
				itemId = widget.getItemId();
			}
		}
		if (itemId <= 0)
		{
			return;
		}
		Recipe recipe = recipeBook.defaultFor(itemManager.canonicalize(itemId));
		if (recipe != null)
		{
			onAdd.accept(recipe);
		}
	}
}
