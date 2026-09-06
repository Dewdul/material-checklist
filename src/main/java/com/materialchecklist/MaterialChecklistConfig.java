package com.materialchecklist;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(ChecklistState.CONFIG_GROUP)
public interface MaterialChecklistConfig extends Config
{
	@ConfigSection(
		name = "Counting",
		description = "What counts toward the materials you own",
		position = 0
	)
	String countingSection = "counting";

	@ConfigSection(
		name = "Materials view",
		description = "How the aggregated materials list is displayed",
		position = 1
	)
	String materialsSection = "materials";

	@ConfigSection(
		name = "Adding from the game",
		description = "Ways to add finished goods without the search box",
		position = 2
	)
	String addingSection = "adding";

	enum AddMenuMode
	{
		ALWAYS("Always"),
		SHIFT("Hold Shift"),
		DISABLED("Disabled");

		private final String label;

		AddMenuMode(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	enum FarmingYield
	{
		AVERAGE("Typical average"),
		MINIMUM("Guaranteed minimum");

		private final String label;

		FarmingYield(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	enum MaterialSort
	{
		MISSING_FIRST("Missing first"),
		MOST_NEEDED("Most needed"),
		ALPHABETICAL("Alphabetical");

		private final String label;

		MaterialSort(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	@ConfigItem(
		keyName = "includeBank",
		name = "Count banked items",
		description = "Include your bank (snapshotted whenever you open it) when counting owned materials",
		section = countingSection,
		position = 0
	)
	default boolean includeBank()
	{
		return true;
	}

	@ConfigItem(
		keyName = "deductOwnedProducts",
		name = "Owned products reduce needs",
		description = "Finished goods you already own count toward a goal, reducing how many to make and the materials for them. Off: materials always cover making the full wanted amount.",
		section = countingSection,
		position = 1
	)
	default boolean countOwnedProducts()
	{
		return false;
	}

	@ConfigItem(
		keyName = "farmingYield",
		name = "Variable crop yields",
		description = "Seeds for variable-yield crops assume a typical harvest (~3x the guaranteed minimum, e.g. ~10 hemp per 3-seed planting) or the worst-case guaranteed minimum",
		section = countingSection,
		position = 2
	)
	default FarmingYield farmingYield()
	{
		return FarmingYield.AVERAGE;
	}

	@ConfigItem(
		keyName = "autoRemoveCompleted",
		name = "Auto-remove finished goods",
		description = "Remove a finished good from the checklist once you own the wanted amount",
		section = countingSection,
		position = 3
	)
	default boolean autoRemoveCompleted()
	{
		return false;
	}

	@ConfigItem(
		keyName = "removeWhenBuilt",
		name = "Remove goods when built",
		description = "Building or applying a tracked part or furniture piece ticks one off the checklist (boat parts, POH furniture and other buildables never enter your inventory, so ownership can't detect them)",
		section = countingSection,
		position = 4
	)
	default boolean removeWhenBuilt()
	{
		return true;
	}

	@ConfigItem(
		keyName = "materialSort",
		name = "Sort materials by",
		description = "Order of the aggregated materials list",
		section = materialsSection,
		position = 0
	)
	default MaterialSort materialSort()
	{
		return MaterialSort.MISSING_FIRST;
	}

	@ConfigItem(
		keyName = "hideCompleted",
		name = "Hide completed materials",
		description = "Hide materials you already have enough of from the Materials tab",
		section = materialsSection,
		position = 1
	)
	default boolean hideCompleted()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showPrices",
		name = "Show GE cost of missing materials",
		description = "Show the Grand Exchange cost to buy what you are still missing",
		section = materialsSection,
		position = 2
	)
	default boolean showPrices()
	{
		return true;
	}

	@ConfigItem(
		keyName = "skillGuideMenu",
		name = "Skill guide right-click",
		description = "Add 'Add to Checklist' to right-click menus in the skill guides",
		section = addingSection,
		position = 0
	)
	default boolean skillGuideMenu()
	{
		return true;
	}

	@ConfigItem(
		keyName = "itemMenuMode",
		name = "Inventory/bank right-click",
		description = "Add 'Add to Checklist' to right-click menus on craftable inventory and bank items",
		section = addingSection,
		position = 1
	)
	default AddMenuMode itemMenuMode()
	{
		return AddMenuMode.SHIFT;
	}

	@ConfigItem(
		keyName = "buildMenuAdd",
		name = "Capture build menu clicks",
		description = "Clicking an entry in the POH furniture or ship customisation menus adds it to the checklist",
		section = addingSection,
		position = 2
	)
	default boolean buildMenuAdd()
	{
		return true;
	}

	@ConfigItem(
		keyName = "buildMenuRequireShift",
		name = "Only while holding Shift",
		description = "Capture build menu clicks only while Shift is held, so ordinary building does not fill the checklist",
		section = addingSection,
		position = 3
	)
	default boolean buildMenuRequireShift()
	{
		return true;
	}

	@ConfigItem(
		keyName = "chatMessageOnAdd",
		name = "Chat message on add",
		description = "Show a chat message when something is added from the game",
		section = addingSection,
		position = 4
	)
	default boolean chatMessageOnAdd()
	{
		return true;
	}
}
