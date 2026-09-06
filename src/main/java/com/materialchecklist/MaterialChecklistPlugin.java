package com.materialchecklist;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "Material Checklist",
	description = "Plan items to craft across all skills and track the raw materials you still need, counting inventory and bank",
	tags = {"materials", "checklist", "crafting", "smithing", "fletching", "herblore", "construction", "skilling", "shopping", "bank"}
)
public class MaterialChecklistPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ItemManager itemManager;

	@Inject
	private RecipeBook recipeBook;

	@Inject
	private ChecklistState state;

	@Inject
	private OwnedItems owned;

	@Inject
	private ChecklistCalculator calculator;

	@Inject
	private GameMenuSupport gameMenus;

	@Inject
	private MaterialChecklistConfig config;

	private MaterialChecklistPanel panel;
	private NavigationButton navButton;

	@Override
	protected void startUp()
	{
		panel = new MaterialChecklistPanel(this, recipeBook, itemManager);
		gameMenus.init(this::addFromGame);

		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "panel_icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Material Checklist")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		clientThread.invoke(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				owned.loadBankSnapshot();
				owned.updateInventory(client.getItemContainer(InventoryID.INV));
			}
			refresh();
		});
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		navButton = null;
		panel = null;
	}

	@Provides
	MaterialChecklistConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(MaterialChecklistConfig.class);
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.INV)
		{
			owned.updateInventory(event.getItemContainer());
			refresh();
		}
		else if (event.getContainerId() == InventoryID.BANK)
		{
			owned.updateBank(event.getItemContainer());
			refresh();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			owned.loadBankSnapshot();
			refresh();
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		clientThread.invoke(() ->
		{
			owned.loadBankSnapshot();
			refresh();
		});
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (ChecklistState.CONFIG_GROUP.equals(event.getGroup()))
		{
			clientThread.invokeLater(this::refresh);
		}
	}

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		gameMenus.onMenuOpened(event);
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		gameMenus.onMenuEntryAdded(event);
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		gameMenus.onMenuOptionClicked(event);
	}

	/**
	 * Recomputes the snapshot on the client thread and hands it to the panel.
	 * Safe to call from any thread.
	 */
	public void refresh()
	{
		clientThread.invoke(() ->
		{
			if (panel == null)
			{
				return;
			}
			GameState gameState = client.getGameState();
			if (gameState == GameState.STARTING || gameState == GameState.UNKNOWN)
			{
				// item cache not loaded yet; a later GameStateChanged will retry
				return;
			}
			ChecklistSnapshot snapshot = calculator.build();
			SwingUtilities.invokeLater(() ->
			{
				if (panel != null)
				{
					panel.render(snapshot);
				}
			});
		});
	}

	/** Called from GameMenuSupport on the client thread. */
	private void addFromGame(Recipe recipe)
	{
		state.addGoal(recipe.name, 1);
		client.addChatMessage(ChatMessageType.CONSOLE, "",
			"Added <col=cc8400>" + recipe.name + "</col> to the Material Checklist.", null);
		refresh();
	}

	// ---- panel callbacks (EDT) ----

	public void addGoal(String recipeName, int quantity)
	{
		state.addGoal(recipeName, quantity);
		refresh();
	}

	public void removeGoal(Goal goal)
	{
		state.removeGoal(goal);
		refresh();
	}

	public void setGoalQuantity(Goal goal, int quantity)
	{
		state.setQuantity(goal, quantity);
		refresh();
	}

	public void toggleCollapsed(Goal goal)
	{
		state.toggleCollapsed(goal);
		refresh();
	}

	public void expandMaterial(Goal parent, int itemId)
	{
		Recipe recipe = recipeBook.defaultFor(itemId);
		if (recipe != null)
		{
			state.expandMaterial(parent, recipe.name);
			refresh();
		}
	}

	public void collapseMaterial(Goal parent, String childRecipeName)
	{
		state.collapseMaterial(parent, childRecipeName);
		refresh();
	}

	public void clearChecklist()
	{
		state.clear();
		refresh();
	}
}
