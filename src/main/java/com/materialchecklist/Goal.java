package com.materialchecklist;

import java.util.ArrayList;
import java.util.List;

/**
 * One node of the user's checklist. Roots are finished goods with a wanted
 * quantity; children are intermediate materials the user chose to craft
 * (their needed quantity is derived from the parent at aggregation time).
 * Persisted as JSON — recipes are referenced by NAME, never by item id,
 * so buildable scenery round-trips like any other product.
 */
public class Goal
{
	public String name;
	public int quantity;
	public boolean collapsed;
	public List<Goal> children = new ArrayList<>();

	public Goal()
	{
	}

	public Goal(String name, int quantity)
	{
		this.name = name;
		this.quantity = quantity;
	}

	public List<Goal> children()
	{
		if (children == null)
		{
			children = new ArrayList<>();
		}
		return children;
	}

	public Goal copy()
	{
		Goal copy = new Goal(name, quantity);
		copy.collapsed = collapsed;
		for (Goal child : children())
		{
			copy.children.add(child.copy());
		}
		return copy;
	}
}
