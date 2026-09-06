package com.materialchecklist;

import com.materialchecklist.ChecklistSnapshot.GoalLine;
import com.materialchecklist.ChecklistSnapshot.MaterialLine;
import com.materialchecklist.ChecklistSnapshot.MaterialRow;
import java.util.ArrayList;
import java.util.HashMap;
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
			List<GoalLine> lines = new ArrayList<>(goals.size());
			for (Goal goal : goals)
			{
				Recipe recipe = recipeBook.get(goal.name);
				if (recipe == null)
				{
					// dataset regenerated and the name vanished; keep it visible so
					// the user can remove it rather than silently dropping data
					lines.add(new GoalLine(goal, null, goal.quantity, 0, goal.collapsed, new ArrayList<>()));
					continue;
				}
				lines.add(build.goalLine(goal, recipe, goal.quantity, 0));
			}
			return lines;
		});

		List<MaterialLine> totals = new ArrayList<>(build.rawNeeded.size());
		long totalMissingCost = 0;
		for (Map.Entry<Integer, Integer> entry : build.rawNeeded.entrySet())
		{
			MaterialLine line = materialLine(entry.getKey(), entry.getValue(), build.rawAlternates.get(entry.getKey()));
			totals.add(line);
			totalMissingCost += line.missingCost;
		}
		totals.sort((a, b) ->
		{
			int cmp = Integer.compare(b.missing(), a.missing());
			return cmp != 0 ? cmp : a.name.compareToIgnoreCase(b.name);
		});
		return new ChecklistSnapshot(goalLines, totals, totalMissingCost, owned.hasBankSnapshot());
	}

	/** Per-build accumulators. */
	private class Build
	{
		final Map<Integer, Integer> rawNeeded = new LinkedHashMap<>();
		final Map<Integer, List<Integer>> rawAlternates = new HashMap<>();
		/** Owned finished/intermediate products already counted against a goal. */
		final Map<Integer, Integer> allocatedProducts = new HashMap<>();

		GoalLine goalLine(Goal goal, Recipe recipe, int wantedUnits, int depth)
		{
			int ownedProducts = 0;
			if (config.countOwnedProducts() && recipe.productId > 0)
			{
				int have = countOwned(java.util.Collections.singletonList(recipe.productId));
				int alreadyAllocated = allocatedProducts.getOrDefault(recipe.productId, 0);
				ownedProducts = Math.min(Math.max(0, have - alreadyAllocated), wantedUnits);
				if (ownedProducts > 0)
				{
					allocatedProducts.merge(recipe.productId, ownedProducts, Integer::sum);
				}
			}
			int unitsToMake = wantedUnits - ownedProducts;
			int batches = unitsToMake <= 0 ? 0 : recipe.batchesFor(unitsToMake);

			List<MaterialRow> rows = new ArrayList<>(recipe.ingredients().size());
			for (Recipe.Ingredient ingredient : recipe.ingredients())
			{
				int required = batches * ingredient.quantity;
				Goal childGoal = depth < MAX_DEPTH ? findChild(goal, ingredient) : null;
				if (childGoal != null)
				{
					Recipe childRecipe = recipeBook.get(childGoal.name);
					GoalLine childLine = goalLine(childGoal, childRecipe, required, depth + 1);
					MaterialLine line = materialLine(ingredient.itemId, required, ingredient.same);
					rows.add(new MaterialRow(line, goal, childLine));
				}
				else
				{
					rawNeeded.merge(ingredient.itemId, required, Integer::sum);
					if (ingredient.same != null && !ingredient.same.isEmpty())
					{
						rawAlternates.put(ingredient.itemId, ingredient.same);
					}
					MaterialLine line = materialLine(ingredient.itemId, required, ingredient.same);
					rows.add(new MaterialRow(line, goal, null));
				}
			}
			return new GoalLine(goal, recipe, wantedUnits, ownedProducts, goal.collapsed, rows);
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
	}

	private MaterialLine materialLine(int itemId, int needed, List<Integer> alternates)
	{
		List<Integer> ids = new ArrayList<>();
		ids.add(itemId);
		if (alternates != null)
		{
			ids.addAll(alternates);
		}
		int inv = 0;
		int bank = 0;
		for (int id : ids)
		{
			inv += owned.inventoryCount(id);
			if (config.includeBank())
			{
				bank += owned.bankCount(id);
			}
		}
		String name = nameOf(itemId);
		boolean craftable = recipeBook.hasRecipeFor(itemId);
		long missingCost = 0;
		int missing = Math.max(0, needed - inv - bank);
		if (config.showPrices() && missing > 0)
		{
			missingCost = (long) itemManager.getItemPrice(itemId) * missing;
		}
		return new MaterialLine(itemId, name, needed, inv, bank, craftable, missingCost);
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

	/** Client thread; memoized. Uses getMembersName to avoid " (Members)" suffixes on F2P worlds. */
	private String nameOf(int itemId)
	{
		return nameCache.computeIfAbsent(itemId, id -> itemManager.getItemComposition(id).getMembersName());
	}
}
