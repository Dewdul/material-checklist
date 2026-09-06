package com.materialchecklist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Validates the bundled wiki-generated recipes.json. */
public class RecipeBookTest
{
	private static RecipeBook book;

	@BeforeClass
	public static void load()
	{
		Gson gson = new GsonBuilder().create();
		book = new RecipeBook(gson);
	}

	@Test
	public void datasetIsLarge()
	{
		assertTrue("expected > 4500 recipes, got " + book.size(), book.size() > 4500);
	}

	@Test
	public void knownRecipesResolve()
	{
		Recipe moltenGlass = book.get("Molten glass (Normal furnace)");
		assertNotNull(moltenGlass);
		assertEquals(1775, moltenGlass.productId);
		assertEquals(2, moltenGlass.ingredients().size());
		// soda ash 1781 + bucket of sand 1783
		assertTrue(moltenGlass.ingredients().stream().anyMatch(i -> i.itemId == 1781 && i.quantity == 1));
		assertTrue(moltenGlass.ingredients().stream().anyMatch(i -> i.itemId == 1783 && i.quantity == 1));

		// steel bar default method should include iron ore (440)
		Recipe steelBar = book.defaultFor(2353);
		assertNotNull(steelBar);
		assertTrue(steelBar.ingredients().stream().anyMatch(i -> i.itemId == 440));
	}

	@Test
	public void searchRanksExactAndPrefixFirst()
	{
		List<Recipe> results = book.search("molten glass", 10);
		assertFalse(results.isEmpty());
		assertTrue(results.get(0).name.toLowerCase().startsWith("molten glass"));
	}

	@Test
	public void rawDataIsWellFormed() throws Exception
	{
		try (InputStream in = RecipeBook.class.getResourceAsStream("recipes.json"))
		{
			assertNotNull("recipes.json must be bundled", in);
			JsonObject root = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			JsonArray recipes = root.getAsJsonArray("recipes");
			Set<String> names = new HashSet<>();
			for (int i = 0; i < recipes.size(); i++)
			{
				JsonObject recipe = recipes.get(i).getAsJsonObject();
				String name = recipe.get("name").getAsString();
				assertFalse("empty name at index " + i, name.isEmpty());
				assertTrue("duplicate name: " + name, names.add(name.toLowerCase()));
				int productId = recipe.get("productId").getAsInt();
				JsonArray ingredients = recipe.getAsJsonArray("ingredients");
				assertTrue("no ingredients: " + name, ingredients.size() > 0);
				for (int j = 0; j < ingredients.size(); j++)
				{
					JsonObject ingredient = ingredients.get(j).getAsJsonObject();
					int itemId = ingredient.get("itemId").getAsInt();
					int quantity = ingredient.get("quantity").getAsInt();
					assertTrue("bad ingredient id in " + name, itemId > 0);
					assertTrue("bad quantity in " + name, quantity > 0);
					assertFalse("recipe consumes its own product: " + name, productId > 0 && itemId == productId);
					if (ingredient.has("same"))
					{
						JsonArray same = ingredient.getAsJsonArray("same");
						for (int k = 0; k < same.size(); k++)
						{
							assertFalse("interchangeable-id set contains the recipe's own product: " + name,
								productId > 0 && same.get(k).getAsInt() == productId);
						}
					}
				}
			}
		}
	}
}
