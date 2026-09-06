package com.materialchecklist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One production recipe from the bundled wiki-generated dataset.
 * The recipe NAME is the primary key everywhere (search, persistence,
 * drill-down); {@code productId} is 0 for buildable scenery (POH furniture
 * variants and the like) that has no backing item.
 */
public class Recipe
{
	public String name;
	public int productId;
	public String skill;
	public int level;
	public int makes;
	public List<Ingredient> ingredients;

	public int batchSize()
	{
		return Math.max(1, makes);
	}

	/** Number of times the recipe must be performed to yield {@code wanted} products. */
	public int batchesFor(int wanted)
	{
		int batch = batchSize();
		return (wanted + batch - 1) / batch;
	}

	/** Item id used for the panel icon; scenery borrows the first ingredient's icon. */
	public int iconItemId()
	{
		if (productId > 0)
		{
			return productId;
		}
		return ingredients == null || ingredients.isEmpty() ? 0 : ingredients.get(0).itemId;
	}

	public List<Ingredient> ingredients()
	{
		return ingredients == null ? Collections.emptyList() : ingredients;
	}

	public static class Ingredient
	{
		public int itemId;
		public int quantity;
		public List<Integer> same;

		/** The canonical id plus any interchangeable ids (e.g. watered saplings). */
		public List<Integer> allIds()
		{
			if (same == null || same.isEmpty())
			{
				return Collections.singletonList(itemId);
			}
			List<Integer> ids = new ArrayList<>(1 + same.size());
			ids.add(itemId);
			ids.addAll(same);
			return ids;
		}
	}
}
