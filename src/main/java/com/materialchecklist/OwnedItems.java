package com.materialchecklist;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;

/**
 * Tracks what the player owns: a live inventory tally plus a bank snapshot
 * captured whenever the bank fires ItemContainerChanged (the only time bank
 * contents are readable) and persisted per RS profile so counts survive
 * between banking trips and sessions. All ids are canonicalized so noted
 * items and placeholders collapse onto the real item.
 *
 * Maps are replaced wholesale (never mutated) so the EDT can read them
 * without locking; updates happen on the client thread.
 */
@Slf4j
@Singleton
public class OwnedItems
{
	private static final String BANK_KEY = "bankSnapshot";

	private final ConfigManager configManager;
	private final ItemManager itemManager;

	private volatile Map<Integer, Integer> inventory = Collections.emptyMap();
	private volatile Map<Integer, Integer> bank = Collections.emptyMap();
	private String lastSavedBank = null;

	@Inject
	OwnedItems(ConfigManager configManager, ItemManager itemManager)
	{
		this.configManager = configManager;
		this.itemManager = itemManager;
	}

	/** Client thread only (canonicalize touches the item cache). */
	public void updateInventory(ItemContainer container)
	{
		inventory = tally(container);
	}

	/** Client thread only. Persists the snapshot for the active RS profile. */
	public void updateBank(ItemContainer container)
	{
		Map<Integer, Integer> tallied = tally(container);
		bank = tallied;
		String csv = toCsv(tallied);
		if (!csv.equals(lastSavedBank))
		{
			lastSavedBank = csv;
			configManager.setRSProfileConfiguration(ChecklistState.CONFIG_GROUP, BANK_KEY, csv);
		}
	}

	/** Loads the persisted bank snapshot for the current RS profile (login / profile change). */
	public void loadBankSnapshot()
	{
		String csv = configManager.getRSProfileConfiguration(ChecklistState.CONFIG_GROUP, BANK_KEY);
		bank = fromCsv(csv);
		lastSavedBank = csv;
	}

	public void clearInventory()
	{
		inventory = Collections.emptyMap();
	}

	public int inventoryCount(int itemId)
	{
		return inventory.getOrDefault(itemId, 0);
	}

	public int bankCount(int itemId)
	{
		return bank.getOrDefault(itemId, 0);
	}

	public boolean hasBankSnapshot()
	{
		return !bank.isEmpty();
	}

	private Map<Integer, Integer> tally(ItemContainer container)
	{
		if (container == null)
		{
			return Collections.emptyMap();
		}
		Map<Integer, Integer> totals = new HashMap<>();
		for (Item item : container.getItems())
		{
			int id = item.getId();
			int quantity = item.getQuantity();
			// empty slots and bank placeholders (quantity 0), plus bank fillers
			if (id <= 0 || quantity <= 0 || id == ItemID.BANK_FILLER)
			{
				continue;
			}
			totals.merge(itemManager.canonicalize(id), quantity, Integer::sum);
		}
		return totals;
	}

	private static String toCsv(Map<Integer, Integer> counts)
	{
		// sorted so identical contents always serialize identically
		// (avoids spurious config writes and cloud re-uploads)
		StringBuilder sb = new StringBuilder(counts.size() * 12);
		for (Map.Entry<Integer, Integer> entry : new TreeMap<>(counts).entrySet())
		{
			if (sb.length() > 0)
			{
				sb.append(';');
			}
			sb.append(entry.getKey()).append(',').append(entry.getValue());
		}
		return sb.toString();
	}

	private static Map<Integer, Integer> fromCsv(String csv)
	{
		if (csv == null || csv.isEmpty())
		{
			return Collections.emptyMap();
		}
		Map<Integer, Integer> counts = new HashMap<>();
		for (String pair : csv.split(";"))
		{
			int comma = pair.indexOf(',');
			if (comma <= 0)
			{
				continue;
			}
			try
			{
				counts.put(Integer.parseInt(pair.substring(0, comma)),
					Integer.parseInt(pair.substring(comma + 1)));
			}
			catch (NumberFormatException e)
			{
				log.warn("Bad bank snapshot entry: {}", pair);
			}
		}
		return counts;
	}
}
