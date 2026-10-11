package com.materialchecklist;

import com.materialchecklist.ChecklistSnapshot.GoalLine;
import com.materialchecklist.ChecklistSnapshot.MaterialLine;
import com.materialchecklist.ChecklistSnapshot.MaterialRow;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.game.ItemManager;

/**
 * Builds a {@link ChecklistSnapshot} from the goal tree, recipe book and
 * owned-item counts. Must run on the CLIENT THREAD — it resolves item names
 * from the game cache.
 *
 * Two passes over the goal tree: the first walks every goal allocating owned
 * finished/intermediate products (greedy, tree order) and accumulating raw
 * requirements; the second builds view lines using the completed allocation
 * ledger, so items counted as an owned product are not double-counted as
 * owned raw materials elsewhere.
 */
@Singleton
public class ChecklistCalculator
{
	/** Cycle guard for pathological recipe chains. */
	private static final int MAX_DEPTH = 8;

	private final ChecklistState state;
	private final RecipeBook recipeBook;
	private final OwnedItems owned;
	private final ItemManager itemManager;
	private final MaterialChecklistConfig config;

	private final Map<Integer, String> nameCache = new ConcurrentHashMap<>();

	@Inject
	ChecklistCalculator(ChecklistState state, RecipeBook recipeBook, OwnedItems owned,
		ItemManager itemManager, MaterialChecklistConfig config)
	{
		this.state = state;
		this.recipeBook = recipeBook;
		this.owned = owned;
		this.itemManager = itemManager;
		this.config = config;
	}

	public ChecklistSnapshot build()
	{
		Build build = new Build();
		List<GoalLine> goalLines = state.read(goals ->
		{
			for (Goal goal : goals)
			{
				Recipe recipe = recipeBook.get(goal.name);
				if (recipe != null)
				{
					build.allocate(goal, recipe, goal.quantity, 0);
				}
			}
			List<GoalLine> lines = new ArrayList<>(goals.size());
			for (Goal goal : goals)
			{
				Recipe recipe = recipeBook.get(goal.name);
				if (recipe == null)
				{
					// dataset regenerated and the name vanished; keep it visible so
					// the user can remove it rather than silently dropping data
					lines.add(new GoalLine(goal, null, goal.quantity, 0, goal.collapsed, 0, "", new ArrayList<>()));
					continue;
				}
				lines.add(build.lines(goal, recipe, 0));
			}
			return lines;
		});

		// one combined line per item: every occurrence (top level or nested
		// under an expanded parent) shows the same have/need numbers
		Map<Integer, MaterialLine> linesById = new LinkedHashMap<>();
		long totalMissingCost = 0;
		for (Map.Entry<Integer, Integer> entry : build.needTotals.entrySet())
		{
			if (entry.getValue() <= 0)
			{
				continue;
			}
			boolean expanded = build.childrenOf.containsKey(entry.getKey());
			MaterialLine line = build.materialLine(entry.getKey(), entry.getValue(),
				build.rawAlternates.get(entry.getKey()), true, expanded);
			linesById.put(entry.getKey(), line);
			// expanded intermediates price as their inputs, not themselves
			totalMissingCost += line.missingCost;
		}

		List<ChecklistSnapshot.TotalEntry> totals = new ArrayList<>(build.topLevelIds.size());
		for (int itemId : build.topLevelIds)
		{
			ChecklistSnapshot.TotalEntry entry = totalEntry(itemId, linesById, build.childrenOf, new java.util.HashSet<>());
			if (entry != null)
			{
				totals.add(entry);
			}
		}
		java.util.Comparator<MaterialLine> byLine = comparatorFor(config.materialSort());
		totals.sort((a, b) -> byLine.compare(a.line, b.line));
		return new ChecklistSnapshot(goalLines, totals, totalMissingCost, owned.hasBankSnapshot());
	}

	/** Builds the Materials-view entry for an item, nesting an expanded item's inputs. */
	private ChecklistSnapshot.TotalEntry totalEntry(int itemId, Map<Integer, MaterialLine> linesById,
		Map<Integer, java.util.LinkedHashSet<Integer>> childrenOf, java.util.Set<Integer> path)
	{
		MaterialLine line = linesById.get(itemId);
		if (line == null || !path.add(itemId))
		{
			return null;
		}
		if (config.hideCompleted() && line.missing() == 0)
		{
			path.remove(itemId);
			return null;
		}
		List<ChecklistSnapshot.TotalEntry> children = new ArrayList<>();
		java.util.LinkedHashSet<Integer> childIds = childrenOf.get(itemId);
		if (childIds != null)
		{
			for (int childId : childIds)
			{
				ChecklistSnapshot.TotalEntry child = totalEntry(childId, linesById, childrenOf, path);
				if (child != null)
				{
					children.add(child);
				}
			}
		}
		path.remove(itemId);
		return new ChecklistSnapshot.TotalEntry(line, children);
	}

	private static java.util.Comparator<MaterialLine> comparatorFor(MaterialChecklistConfig.MaterialSort sort)
	{
		java.util.Comparator<MaterialLine> byName = (a, b) -> a.name.compareToIgnoreCase(b.name);
		switch (sort)
		{
			case ALPHABETICAL:
				return byName;
			case MOST_NEEDED:
				return java.util.Comparator.comparingInt((MaterialLine l) -> -l.needed).thenComparing(byName);
			case MISSING_FIRST:
			default:
				return java.util.Comparator.comparingInt((MaterialLine l) -> -l.missing()).thenComparing(byName);
		}
	}

	/** Per-build accumulators and the two tree passes. */
	private class Build
	{
		/** Combined need per item id across every counted source. */
		final Map<Integer, Integer> needTotals = new LinkedHashMap<>();
		/** Direct ingredients of root goals, in first-seen order. */
		final java.util.LinkedHashSet<Integer> topLevelIds = new java.util.LinkedHashSet<>();
		/** Expanded item id -> the item ids of its counted inputs. */
		final Map<Integer, java.util.LinkedHashSet<Integer>> childrenOf = new LinkedHashMap<>();
		final Map<Integer, List<Integer>> rawAlternates = new HashMap<>();
		/** Owned products consumed by goals, by product item id. */
		final Map<Integer, Integer> allocatedProducts = new HashMap<>();
		/** Pass-one numbers per goal node: {wantedUnits, ownedUsed, batches}. */
		final Map<Goal, int[]> nodeNumbers = new IdentityHashMap<>();

		void allocate(Goal goal, Recipe recipe, int wantedUnits, int depth)
		{
			allocate(goal, recipe, wantedUnits, depth, null);
		}

		/**
		 * Pass one: allocate owned products and accumulate the Materials-view
		 * needs. Direct root ingredients always count in full; an expanded
		 * intermediate's inputs count for its SHORTFALL (what must actually be
		 * crafted after owned units, allocated greedily in tree order), and
		 * are linked to their parent item for nested display.
		 */
		private void allocate(Goal goal, Recipe recipe, int wantedUnits, int depth, Integer parentItemId)
		{
			int ownedUsed = 0;
			// expanded intermediates always plan from the shortfall; root goals
			// deduct owned products only when the setting asks for it
			if (recipe.productId > 0 && (depth > 0 || config.countOwnedProducts()))
			{
				int have = countOwned(recipe.productId);
				int alreadyAllocated = allocatedProducts.getOrDefault(recipe.productId, 0);
				ownedUsed = Math.min(Math.max(0, have - alreadyAllocated), wantedUnits);
				if (ownedUsed > 0)
				{
					allocatedProducts.merge(recipe.productId, ownedUsed, Integer::sum);
				}
			}
			int unitsToMake = wantedUnits - ownedUsed;
			int batches = unitsToMake <= 0 ? 0 : effectiveBatchesFor(recipe, unitsToMake);
			nodeNumbers.put(goal, new int[]{wantedUnits, ownedUsed, batches});

			for (Recipe.Ingredient ingredient : recipe.ingredients())
			{
				int required = batches * ingredient.quantity;
				if (required > 0)
				{
					needTotals.merge(ingredient.itemId, required, Integer::sum);
					if (ingredient.same != null && !ingredient.same.isEmpty())
					{
						rawAlternates.put(ingredient.itemId, ingredient.same);
					}
					if (depth == 0)
					{
						topLevelIds.add(ingredient.itemId);
					}
					if (parentItemId != null)
					{
						childrenOf.computeIfAbsent(parentItemId, k -> new java.util.LinkedHashSet<>())
							.add(ingredient.itemId);
					}
				}
				Goal childGoal = depth < MAX_DEPTH ? findChild(goal, ingredient) : null;
				if (childGoal != null)
				{
					allocate(childGoal, recipeBook.get(childGoal.name), required, depth + 1, ingredient.itemId);
				}
			}
		}

		/** Pass two: build view lines with the completed allocation ledger. */
		GoalLine lines(Goal goal, Recipe recipe, int depth)
		{
			int[] numbers = nodeNumbers.get(goal);
			int wantedUnits = numbers[0];
			int ownedUsed = numbers[1];
			int batches = numbers[2];

			List<MaterialRow> rows = new ArrayList<>(recipe.ingredients().size());
			for (Recipe.Ingredient ingredient : recipe.ingredients())
			{
				int required = batches * ingredient.quantity;
				Goal childGoal = depth < MAX_DEPTH ? findChild(goal, ingredient) : null;
				if (childGoal != null)
				{
					GoalLine childLine = lines(childGoal, recipeBook.get(childGoal.name), depth + 1);
					// the expanded row shows full owned counts; the child line's
					// own allocation already accounts for what is consumed
					MaterialLine line = materialLine(ingredient.itemId, required, ingredient.same, false);
					rows.add(new MaterialRow(line, goal, childLine));
				}
				else
				{
					MaterialLine line = materialLine(ingredient.itemId, required, ingredient.same, true);
					rows.add(new MaterialRow(line, goal, null));
				}
			}
			// tools (saw, hammer, persistent one-offs like wind motes) are NOT
			// materials — they surface only as a note in the goal tooltip
			StringBuilder toolsText = new StringBuilder();
			for (int toolId : recipe.tools())
			{
				if (toolsText.length() > 0)
				{
					toolsText.append(", ");
				}
				toolsText.append(nameOf(toolId));
			}
			return new GoalLine(goal, recipe, wantedUnits, ownedUsed, goal.collapsed,
				iconFor(recipe), toolsText.toString(), rows);
		}

		private Goal findChild(Goal goal, Recipe.Ingredient ingredient)
		{
			for (Goal child : goal.children())
			{
				Recipe childRecipe = recipeBook.get(child.name);
				if (childRecipe != null && childRecipe.productId > 0
					&& ingredient.allIds().contains(childRecipe.productId))
				{
					return child;
				}
			}
			return null;
		}

		/**
		 * Builds one material line. When {@code subtractAllocations} is set,
		 * owned units already allocated to goals as finished products are
		 * removed from the displayed counts (bank first, then inventory) so
		 * the same physical items are never counted twice.
		 */
		MaterialLine materialLine(int itemId, int needed, List<Integer> alternates, boolean subtractAllocations)
		{
			return materialLine(itemId, needed, alternates, subtractAllocations, false);
		}

		MaterialLine materialLine(int itemId, int needed, List<Integer> alternates, boolean subtractAllocations, boolean expanded)
		{
			List<Integer> ids = new ArrayList<>();
			ids.add(itemId);
			if (alternates != null)
			{
				ids.addAll(alternates);
			}
			int inv = 0;
			int bank = 0;
			int allocated = 0;
			for (int id : ids)
			{
				inv += owned.inventoryCount(id);
				if (config.includeBank())
				{
					bank += owned.bankCount(id);
				}
				if (subtractAllocations)
				{
					allocated += allocatedProducts.getOrDefault(id, 0);
				}
			}
			int fromBank = Math.min(bank, allocated);
			bank -= fromBank;
			inv = Math.max(0, inv - (allocated - fromBank));

			String name = nameOf(itemId);
			boolean craftable = recipeBook.hasRecipeFor(itemId);
			long missingCost = 0;
			int missing = Math.max(0, needed - inv - bank);
			// an expanded intermediate is crafted, not bought — its inputs carry the cost
			if (config.showPrices() && missing > 0 && !expanded)
			{
				missingCost = (long) itemManager.getItemPrice(itemId) * missing;
			}
			return new MaterialLine(itemId, name, needed, inv, bank, craftable, missingCost, expanded);
		}

		private int countOwned(int itemId)
		{
			return countOwned(Collections.singletonList(itemId));
		}

		private int countOwned(List<Integer> ids)
		{
			int total = 0;
			for (int id : ids)
			{
				total += owned.inventoryCount(id);
				if (config.includeBank())
				{
					total += owned.bankCount(id);
				}
			}
			return total;
		}
	}

	/**
	 * Batches (plantings) needed for the wanted units. Variable-yield crops
	 * record the guaranteed minimum per planting; under the typical-yield
	 * assumption (~3.3x, e.g. ~10 hemp instead of 3 per 3-seed planting)
	 * far fewer plantings are assumed.
	 */
	private int effectiveBatchesFor(Recipe recipe, int units)
	{
		int perBatch = recipe.batchSize();
		if (recipe.variableYield
			&& config.farmingYield() == MaterialChecklistConfig.FarmingYield.AVERAGE)
		{
			perBatch = Math.max(perBatch, (int) Math.round(perBatch * 10.0 / 3.0));
		}
		return (units + perBatch - 1) / perBatch;
	}

	/**
	 * Icon item for a goal header. Some wiki-sourced product ids (notably
	 * Sailing part combos) have no usable sprite in the client cache — those
	 * resolve to a "null" composition name; fall back to an ingredient's icon.
	 */
	private int iconFor(Recipe recipe)
	{
		int id = recipe.iconItemId();
		if (id > 0 && !"null".equalsIgnoreCase(nameOf(id)))
		{
			return id;
		}
		for (Recipe.Ingredient ingredient : recipe.ingredients())
		{
			if (!"null".equalsIgnoreCase(nameOf(ingredient.itemId)))
			{
				return ingredient.itemId;
			}
		}
		return id;
	}

	/** Client thread; memoized. Uses getMembersName to avoid " (Members)" suffixes on F2P worlds. */
	private String nameOf(int itemId)
	{
		return nameCache.computeIfAbsent(itemId, id -> itemManager.getItemComposition(id).getMembersName());
	}
}
