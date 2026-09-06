package com.materialchecklist;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.Point;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.JagexColors;

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
	private static final String ADD_OPTION = "Add to Material Checklist";

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
		// [row rect, level text, item graphic, description text]
		Widget list = client.getWidget(InterfaceID.SkillGuideV2.LIST);
		if (list != null && !list.isHidden())
		{
			Widget[] children = list.getDynamicChildren();
			for (int i = 0; i + 3 < children.length; i += 4)
			{
				if (children[i].getBounds().contains(mouse.getX(), mouse.getY()))
				{
					offerEntry(children[i + 2]);
					return;
				}
			}
		}

		// Legacy skill guide (group 214): icon child i on ICONS, text children
		// 2i (level) / 2i+1 (description) on INFO
		Widget icons = client.getWidget(InterfaceID.SkillGuide.ICONS);
		Widget info = client.getWidget(InterfaceID.SkillGuide.INFO);
		if (icons != null && !icons.isHidden() && info != null)
		{
			Widget[] iconChildren = icons.getDynamicChildren();
			for (int i = 0; i < iconChildren.length; i++)
			{
				Widget description = info.getChild(i * 2 + 1);
				if (iconChildren[i].getBounds().contains(mouse.getX(), mouse.getY())
					|| (description != null && description.getBounds().contains(mouse.getX(), mouse.getY())))
				{
					offerEntry(iconChildren[i]);
					return;
				}
			}
		}
	}

	private void offerEntry(Widget iconWidget)
	{
		int itemId = iconWidget.getItemId();
		// -1 = sprite-override rows; BLANKOBJECT = invisible dummy rows
		if (itemId <= 0 || itemId == ItemID.BLANKOBJECT)
		{
			return;
		}
		int canonical = itemManager.canonicalize(itemId);
		Recipe recipe = recipeBook.defaultFor(canonical);
		if (recipe == null)
		{
			return;
		}
		String name = itemManager.getItemComposition(canonical).getMembersName();
		client.getMenu().createMenuEntry(-1)
			.setOption(ADD_OPTION)
			.setTarget(JagexColors.MENU_TARGET_TAG + name)
			.setType(MenuAction.RUNELITE)
			.setItemId(canonical)
			.onClick(e -> onAdd.accept(recipe));
	}

	/** Client thread; fires per entry per frame — keep cheap. */
	void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!config.itemMenu() || onAdd == null || !"Examine".equals(event.getOption()))
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
		Recipe recipe = recipeBook.defaultFor(canonical);
		if (recipe == null)
		{
			return;
		}
		client.getMenu().createMenuEntry(-1)
			.setOption(ADD_OPTION)
			.setTarget(event.getTarget())
			.setType(MenuAction.RUNELITE)
			.setItemId(canonical)
			.onClick(e -> onAdd.accept(recipe));
	}

	/** Client thread. Passive capture in POH furniture / ship customisation menus. */
	void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!config.buildMenuShiftAdd() || onAdd == null || !client.isKeyPressed(KeyCode.KC_SHIFT))
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
