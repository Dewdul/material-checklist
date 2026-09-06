package com.materialchecklist;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads the bundled recipes.json (generated from the OSRS Wiki by
 * tools/generate_recipes.py) and indexes it by name and product item id.
 */
@Slf4j
@Singleton
public class RecipeBook
{
	private final Map<String, Recipe> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
	private final Map<Integer, List<Recipe>> byProductId = new HashMap<>();
	private final Map<Integer, List<Recipe>> byIngredient = new HashMap<>();

	private static class RecipeFile
	{
		String generated;
		String source;
		@SerializedName("recipes")
		List<Recipe> recipes;
	}

	@Inject
	public RecipeBook(Gson gson)
	{
		try (InputStream in = RecipeBook.class.getResourceAsStream("recipes.json"))
		{
			if (in == null)
			{
				log.error("recipes.json missing from plugin resources");
				return;
			}
			RecipeFile file = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), RecipeFile.class);
			for (Recipe recipe : file.recipes)
			{
				if (recipe.name == null || recipe.ingredients == null || recipe.ingredients.isEmpty())
				{
					continue;
				}
				if (byName.putIfAbsent(recipe.name, recipe) != null)
				{
					continue;
				}
				if (recipe.productId > 0)
				{
					byProductId.computeIfAbsent(recipe.productId, k -> new ArrayList<>()).add(recipe);
				}
				for (Recipe.Ingredient ingredient : recipe.ingredients())
				{
					for (int id : ingredient.allIds())
					{
						byIngredient.computeIfAbsent(id, k -> new ArrayList<>()).add(recipe);
					}
				}
			}
			log.debug("Loaded {} recipes (generated {})", byName.size(), file.generated);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	public Recipe get(String name)
	{
		return name == null ? null : byName.get(name);
	}

	public boolean hasRecipeFor(int itemId)
	{
		return byProductId.containsKey(itemId);
	}

	public int size()
	{
		return byName.size();
	}

	/**
	 * The default production method for an item: fewest distinct ingredients,
	 * ties broken toward MORE total raw material (never understate a shopping
	 * list), then the shorter name.
	 */
	public Recipe defaultFor(int itemId)
	{
		List<Recipe> candidates = byProductId.get(itemId);
		if (candidates == null || candidates.isEmpty())
		{
			return null;
		}
		Recipe best = null;
		for (Recipe r : candidates)
		{
			if (best == null || compareSimplicity(r, best) < 0)
			{
				best = r;
			}
		}
		return best;
	}

	private static int compareSimplicity(Recipe a, Recipe b)
	{
		int cmp = Integer.compare(a.ingredients().size(), b.ingredients().size());
		if (cmp != 0)
		{
			return cmp;
		}
		cmp = Integer.compare(totalRaw(b), totalRaw(a));
		if (cmp != 0)
		{
			return cmp;
		}
		return Integer.compare(a.name.length(), b.name.length());
	}

	private static int totalRaw(Recipe r)
	{
		int total = 0;
		for (Recipe.Ingredient i : r.ingredients())
		{
			total += i.quantity;
		}
		return total;
	}

	/**
	 * All recipes that could be meant by a product item id / display name,
	 * for offering a method choice at add time. Prefers the product-id index;
	 * falls back to exact and "Name (variant)" name matches. Sorted by level
	 * requirement, then name.
	 */
	public List<Recipe> candidatesFor(int productId, String productName)
	{
		List<Recipe> matches = new ArrayList<>();
		List<Recipe> byId = productId > 0 ? byProductId.get(productId) : null;
		if (byId != null && !byId.isEmpty())
		{
			matches.addAll(byId);
		}
		else if (productName != null && !productName.isEmpty())
		{
			Recipe exact = byName.get(productName);
			if (exact != null)
			{
				matches.add(exact);
			}
			// both "Name (variant)" and potion-style "Name(3)" spellings
			String spaced = productName.toLowerCase() + " (";
			String unspaced = productName.toLowerCase() + "(";
			for (Map.Entry<String, Recipe> entry : byName.entrySet())
			{
				String key = entry.getKey().toLowerCase();
				if (key.startsWith(spaced) || key.startsWith(unspaced))
				{
					matches.add(entry.getValue());
				}
			}
		}
		matches.sort((a, b) ->
		{
			int cmp = Integer.compare(a.level, b.level);
			return cmp != 0 ? cmp : a.name.compareToIgnoreCase(b.name);
		});
		return matches;
	}

	/** All recipes that produce the given item (empty when none do). */
	public List<Recipe> producersOf(int itemId)
	{
		List<Recipe> list = byProductId.get(itemId);
		return list == null ? Collections.emptyList() : list;
	}

	/** All recipes that consume the given item as an ingredient. */
	public List<Recipe> consumersOf(int itemId)
	{
		List<Recipe> list = byIngredient.get(itemId);
		return list == null ? Collections.emptyList() : list;
	}

	/**
	 * All production methods for the same product as {@code recipe}
	 * (including itself). Scenery products cannot be grouped by id and
	 * return just the recipe itself.
	 */
	public List<Recipe> variantsFor(Recipe recipe)
	{
		if (recipe == null)
		{
			return Collections.emptyList();
		}
		if (recipe.productId > 0)
		{
			List<Recipe> variants = byProductId.get(recipe.productId);
			if (variants != null && variants.size() > 1)
			{
				return variants;
			}
		}
		return Collections.singletonList(recipe);
	}

	/**
	 * Finds a recipe by product display name: exact name match first, then
	 * the simplest "Name (variant)" entry. Used when a game widget's item id
	 * has no direct productId match in the dataset.
	 */
	public Recipe bestForProductName(String productName)
	{
		if (productName == null || productName.isEmpty())
		{
			return null;
		}
		Recipe exact = byName.get(productName);
		if (exact != null)
		{
			return exact;
		}
		String prefix = productName.toLowerCase() + " (";
		Recipe best = null;
		for (Map.Entry<String, Recipe> entry : byName.entrySet())
		{
			if (entry.getKey().toLowerCase().startsWith(prefix))
			{
				Recipe candidate = entry.getValue();
				if (best == null || compareSimplicity(candidate, best) < 0)
				{
					best = candidate;
				}
			}
		}
		return best;
	}

	/**
	 * Ranked substring search over recipe names: exact match first, then
	 * prefix, then contains; shorter names win ties. Cheap enough to run
	 * per keystroke on the EDT (~5k names).
	 */
	public List<Recipe> search(String query, int limit)
	{
		String needle = query == null ? "" : query.trim().toLowerCase();
		if (needle.isEmpty())
		{
			return Collections.emptyList();
		}
		List<Recipe> matches = new ArrayList<>();
		for (Map.Entry<String, Recipe> entry : byName.entrySet())
		{
			if (entry.getKey().toLowerCase().contains(needle))
			{
				matches.add(entry.getValue());
			}
		}
		matches.sort((a, b) ->
		{
			int cmp = Integer.compare(rank(a.name, needle), rank(b.name, needle));
			if (cmp != 0)
			{
				return cmp;
			}
			cmp = Integer.compare(a.name.length(), b.name.length());
			if (cmp != 0)
			{
				return cmp;
			}
			return a.name.compareToIgnoreCase(b.name);
		});
		return matches.size() > limit ? matches.subList(0, limit) : matches;
	}

	private static int rank(String name, String needle)
	{
		String lower = name.toLowerCase();
		if (lower.equals(needle))
		{
			return 0;
		}
		return lower.startsWith(needle) ? 1 : 2;
	}
}
