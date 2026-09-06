package com.materialchecklist;

import com.google.inject.Provides;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.OverlayMenuClicked;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
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

	@Inject
	private EventBus eventBus;

	private MaterialChecklistPanel panel;
	private NavigationButton navButton;

	@Override
	protected void startUp()
	{
		panel = new MaterialChecklistPanel(this, recipeBook, itemManager);
		gameMenus.init(this::addFromGame, this::builtFromGame);

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
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			// logged out: the live inventory tally no longer applies (and must
			// not bleed into a different account's counts on the next login)
			owned.clearInventory();
			refresh();
		}
	}

	@Subscribe
	public void onProfileChanged(ProfileChanged event)
	{
		// RuneLite config profile switched: the goals key now points at a
		// different profile's data — reload rather than overwriting it
		state.reload();
		refresh();
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
			autoRemoveCompletedGoals();
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

	/** Client thread. Removes root goals whose wanted amount is now owned. */
	private void autoRemoveCompletedGoals()
	{
		if (!config.autoRemoveCompleted())
		{
			return;
		}
		java.util.List<Goal> completed = state.read(goals ->
		{
			java.util.List<Goal> done = new java.util.ArrayList<>();
			for (Goal goal : goals)
			{
				Recipe recipe = recipeBook.get(goal.name);
				if (recipe == null || recipe.productId <= 0)
				{
					continue;
				}
				int ownedCount = owned.inventoryCount(recipe.productId)
					+ (config.includeBank() ? owned.bankCount(recipe.productId) : 0);
				if (ownedCount >= goal.quantity)
				{
					done.add(goal);
				}
			}
			return done;
		});
		for (Goal goal : completed)
		{
			state.removeGoal(goal);
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				client.addChatMessage(ChatMessageType.CONSOLE, "",
					"Checklist complete: <col=1e9e00>" + goal.quantity + " x " + goal.name + "</col> — removed.", null);
			}
		}
	}

	/** Called from GameMenuSupport on the client thread: a tracked thing was built. */
	private void builtFromGame(java.util.Collection<String> recipeNames)
	{
		ChecklistState.BuiltResult result = state.consumeBuilt(recipeNames);
		if (result == null)
		{
			return;
		}
		client.addChatMessage(ChatMessageType.CONSOLE, "", result.remaining > 0
			? "Built <col=cc8400>" + result.name + "</col> — " + result.remaining + " left on the Material Checklist."
			: "Built <col=cc8400>" + result.name + "</col> — removed from the Material Checklist.", null);
		refresh();
	}

	/** Called from GameMenuSupport on the client thread. */
	private void addFromGame(Recipe recipe)
	{
		state.addGoal(recipe.name, 1);
		if (config.chatMessageOnAdd())
		{
			client.addChatMessage(ChatMessageType.CONSOLE, "",
				"Added <col=cc8400>" + recipe.name + "</col> to the Material Checklist.", null);
		}
		if (config.openPanelOnAdd())
		{
			final NavigationButton button = navButton;
			if (button != null)
			{
				// openPanel must run on the EDT; this handler is on the client thread
				SwingUtilities.invokeLater(() -> clientToolbar.openPanel(button));
			}
		}
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

	/** Switches a goal to another production method, pruning drill-downs the new method lacks. */
	public void changeGoalMethod(Goal goal, Recipe newRecipe)
	{
		state.changeMethod(goal, newRecipe.name);
		for (Goal child : new java.util.ArrayList<>(goal.children()))
		{
			Recipe childRecipe = recipeBook.get(child.name);
			boolean stillUsed = childRecipe != null && childRecipe.productId > 0
				&& newRecipe.ingredients().stream().anyMatch(i -> i.allIds().contains(childRecipe.productId));
			if (!stillUsed)
			{
				state.collapseMaterial(goal, child.name);
			}
		}
		refresh();
	}

	public void clearChecklist()
	{
		state.clear();
		refresh();
	}

	/** Promotes a material to its own root goal (the user plans to craft it). */
	public void addMaterialAsGoal(int itemId, int quantity)
	{
		Recipe recipe = recipeBook.defaultFor(itemId);
		if (recipe != null)
		{
			state.addGoal(recipe.name, quantity);
			refresh();
		}
	}

	/**
	 * Opens this plugin's page in the RuneLite settings sidebar. There is no
	 * public API for this; the established hub pattern (Watchdog and others)
	 * is a synthetic overlay-config menu click, which ConfigPlugin handles by
	 * opening the poster plugin's configuration. Fails silently if RuneLite
	 * ever stops handling it.
	 */
	public void openConfiguration()
	{
		eventBus.post(new OverlayMenuClicked(
			new OverlayMenuEntry(MenuAction.RUNELITE_OVERLAY_CONFIG, null, null),
			new ConfigLinkOverlay(this)));
	}

	private static class ConfigLinkOverlay extends Overlay
	{
		ConfigLinkOverlay(Plugin plugin)
		{
			super(plugin);
		}

		@Override
		public Dimension render(Graphics2D graphics)
		{
			return null;
		}
	}
}
