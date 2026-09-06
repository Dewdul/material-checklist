package com.materialchecklist;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * The user's goal list plus persistence. Mutations may come from the EDT
 * (panel actions) and the client thread (menu clicks); the view model holds
 * live {@link Goal} references and every access goes through this monitor,
 * so mutations can use identity.
 */
@Slf4j
@Singleton
public class ChecklistState
{
	static final String CONFIG_GROUP = "materialchecklist";
	private static final String GOALS_KEY = "goals";

	private final ConfigManager configManager;
	private final Gson gson;
	private final List<Goal> goals = new ArrayList<>();

	@Inject
	ChecklistState(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
		load();
	}

	private void load()
	{
		String json = configManager.getConfiguration(CONFIG_GROUP, GOALS_KEY);
		if (json == null || json.isEmpty())
		{
			return;
		}
		try
		{
			List<Goal> loaded = gson.fromJson(json, new TypeToken<List<Goal>>()
			{
			}.getType());
			if (loaded != null)
			{
				for (Goal goal : loaded)
				{
					if (goal != null && goal.name != null && goal.quantity > 0)
					{
						goals.add(goal);
					}
				}
			}
		}
		catch (JsonSyntaxException e)
		{
			log.warn("Could not parse saved goals", e);
		}
	}

	private void save()
	{
		configManager.setConfiguration(CONFIG_GROUP, GOALS_KEY, gson.toJson(goals));
	}

	/** Runs {@code fn} over the live goal list while holding the state lock. */
	public synchronized <T> T read(Function<List<Goal>, T> fn)
	{
		return fn.apply(goals);
	}

	public synchronized boolean isEmpty()
	{
		return goals.isEmpty();
	}

	/** Adds quantity to an existing root goal for the recipe, or creates one. */
	public synchronized void addGoal(String recipeName, int quantity)
	{
		for (Goal goal : goals)
		{
			if (goal.name.equalsIgnoreCase(recipeName))
			{
				goal.quantity += quantity;
				save();
				return;
			}
		}
		goals.add(new Goal(recipeName, quantity));
		save();
	}

	public synchronized void removeGoal(Goal target)
	{
		if (goals.removeIf(g -> g == target))
		{
			save();
		}
	}

	public synchronized void setQuantity(Goal target, int quantity)
	{
		if (goals.contains(target) && quantity > 0)
		{
			target.quantity = quantity;
			save();
		}
	}

	public synchronized void toggleCollapsed(Goal target)
	{
		target.collapsed = !target.collapsed;
		save();
	}

	/** Marks an ingredient of {@code parent} as "craft this" by adding a child goal. */
	public synchronized void expandMaterial(Goal parent, String childRecipeName)
	{
		for (Goal child : parent.children())
		{
			if (child.name.equalsIgnoreCase(childRecipeName))
			{
				return;
			}
		}
		parent.children().add(new Goal(childRecipeName, 0));
		save();
	}

	/** Reverts an expanded ingredient back to a raw material. */
	public synchronized void collapseMaterial(Goal parent, String childRecipeName)
	{
		if (parent.children().removeIf(c -> c.name.equalsIgnoreCase(childRecipeName)))
		{
			save();
		}
	}

	public synchronized void clear()
	{
		goals.clear();
		save();
	}
}
