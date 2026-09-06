package com.materialchecklist;

import java.util.List;

/**
 * Immutable view model handed from the client thread to the EDT.
 * Everything the panel renders comes from here; the only live references
 * are the {@link Goal} nodes used to route panel actions back to state.
 */
public class ChecklistSnapshot
{
	/** Goods view: one entry per root goal. */
	public final List<GoalLine> goals;
	/** Materials view: aggregated raw materials across every goal. */
	public final List<MaterialLine> totals;
	/** GE cost of everything still missing (0 when prices are disabled). */
	public final long missingCost;
	public final boolean hasBankSnapshot;

	public ChecklistSnapshot(List<GoalLine> goals, List<MaterialLine> totals, long missingCost, boolean hasBankSnapshot)
	{
		this.goals = goals;
		this.totals = totals;
		this.missingCost = missingCost;
		this.hasBankSnapshot = hasBankSnapshot;
	}

	/** One aggregated raw material. */
	public static class MaterialLine
	{
		public final int itemId;
		public final String name;
		public final int needed;
		public final int inventory;
		public final int bank;
		/** A recipe exists for this material, so it can be drilled into. */
		public final boolean craftable;
		/** GE cost of the missing amount (0 when prices disabled or untradeable). */
		public final long missingCost;
		/** A required tool: needed once, never scaled by batches. */
		public final boolean tool;

		public MaterialLine(int itemId, String name, int needed, int inventory, int bank, boolean craftable, long missingCost, boolean tool)
		{
			this.itemId = itemId;
			this.name = name;
			this.needed = needed;
			this.inventory = inventory;
			this.bank = bank;
			this.craftable = craftable;
			this.missingCost = missingCost;
			this.tool = tool;
		}

		public int have()
		{
			return inventory + bank;
		}

		public int missing()
		{
			return Math.max(0, needed - have());
		}
	}

	/** A goal (root finished good, or an expanded intermediate). */
	public static class GoalLine
	{
		/** Live state node — used for panel callbacks; do not mutate directly. */
		public final Goal goal;
		/** Null when the recipe vanished from a regenerated dataset. */
		public final Recipe recipe;
		/** Wanted units (roots: goal quantity; children: derived from parent). */
		public final int units;
		/** Finished products already owned (inventory + bank). */
		public final int owned;
		public final boolean collapsed;
		/** Validated icon item id (0 = none); some product items have no sprite. */
		public final int iconItemId;
		public final List<MaterialRow> rows;

		public GoalLine(Goal goal, Recipe recipe, int units, int owned, boolean collapsed, int iconItemId, List<MaterialRow> rows)
		{
			this.goal = goal;
			this.recipe = recipe;
			this.units = units;
			this.owned = owned;
			this.collapsed = collapsed;
			this.iconItemId = iconItemId;
			this.rows = rows;
		}
	}

	/** One ingredient row beneath a goal. */
	public static class MaterialRow
	{
		public final MaterialLine line;
		/** The goal this row belongs to (live reference, for expand/collapse). */
		public final Goal parentGoal;
		/** Set when the user expanded this material into its own recipe. */
		public final GoalLine child;

		public MaterialRow(MaterialLine line, Goal parentGoal, GoalLine child)
		{
			this.line = line;
			this.parentGoal = parentGoal;
			this.child = child;
		}

		public boolean expanded()
		{
			return child != null;
		}
	}
}
