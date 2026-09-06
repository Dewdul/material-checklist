package com.materialchecklist;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(ChecklistState.CONFIG_GROUP)
public interface MaterialChecklistConfig extends Config
{
	@ConfigItem(
		keyName = "includeBank",
		name = "Count banked items",
		description = "Include your bank (snapshotted whenever you open it) when counting owned materials",
		position = 0
	)
	default boolean includeBank()
	{
		return true;
	}

	@ConfigItem(
		keyName = "countOwnedProducts",
		name = "Count owned products",
		description = "Finished goods you already own reduce the materials needed",
		position = 1
	)
	default boolean countOwnedProducts()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showPrices",
		name = "Show GE cost of missing materials",
		description = "Show the Grand Exchange cost to buy what you are still missing",
		position = 2
	)
	default boolean showPrices()
	{
		return true;
	}

	@ConfigItem(
		keyName = "skillGuideMenu",
		name = "Skill guide right-click",
		description = "Add 'Add to Material Checklist' to right-click menus in the skill guides",
		position = 3
	)
	default boolean skillGuideMenu()
	{
		return true;
	}

	@ConfigItem(
		keyName = "itemMenu",
		name = "Inventory/bank right-click",
		description = "Add 'Add to Material Checklist' to right-click menus on craftable inventory and bank items",
		position = 4
	)
	default boolean itemMenu()
	{
		return false;
	}

	@ConfigItem(
		keyName = "buildMenuShiftAdd",
		name = "Shift-click build menus",
		description = "Shift-clicking an entry in the POH furniture or ship customisation menus adds it to the checklist",
		position = 5
	)
	default boolean buildMenuShiftAdd()
	{
		return true;
	}
}
