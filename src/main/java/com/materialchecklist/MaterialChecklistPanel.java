package com.materialchecklist;

import com.materialchecklist.ChecklistSnapshot.GoalLine;
import com.materialchecklist.ChecklistSnapshot.MaterialLine;
import com.materialchecklist.ChecklistSnapshot.MaterialRow;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.util.SwingUtil;

class MaterialChecklistPanel extends PluginPanel
{
	private static final String CARD_MAIN = "main";
	private static final String CARD_SEARCH = "search";
	private static final int SEARCH_LIMIT = 25;
	private static final int INDENT_PER_LEVEL = 10;

	private final MaterialChecklistPlugin plugin;
	private final RecipeBook recipeBook;
	private final ItemManager itemManager;

	private final IconTextField searchBar = new IconTextField();
	private final JSpinner quantitySpinner = new JSpinner(new SpinnerNumberModel(1, 1, 99999, 1));
	private final JPanel searchResultsList = new JPanel(new DynamicGridLayout(0, 1, 0, 2));
	private final JPanel materialsList = new JPanel(new DynamicGridLayout(0, 1, 0, 2));
	private final JPanel goodsList = new JPanel(new DynamicGridLayout(0, 1, 0, 4));
	private final JLabel costLabel = new JLabel();
	private final JLabel bankNoteLabel = new JLabel();
	private final CardLayout cardLayout = new CardLayout();
	private final JPanel cards = new JPanel();

	private ChecklistSnapshot snapshot;

	MaterialChecklistPanel(MaterialChecklistPlugin plugin, RecipeBook recipeBook, ItemManager itemManager)
	{
		super(false);
		this.plugin = plugin;
		this.recipeBook = recipeBook;
		this.itemManager = itemManager;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

		add(buildHeader(), BorderLayout.NORTH);

		cards.setLayout(cardLayout);
		cards.setBackground(ColorScheme.DARK_GRAY_COLOR);
		cards.add(buildMainCard(), CARD_MAIN);
		cards.add(buildSearchCard(), CARD_SEARCH);
		add(cards, BorderLayout.CENTER);
	}

	private JPanel buildHeader()
	{
		JPanel header = new JPanel(new BorderLayout(0, 6));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));

		JLabel title = new JLabel("Material Checklist");
		title.setForeground(Color.WHITE);
		title.setFont(FontManager.getRunescapeBoldFont());

		JLabel clear = new JLabel("Clear");
		clear.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		clear.setFont(FontManager.getRunescapeSmallFont());
		clear.setOpaque(true);
		clear.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		clear.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(2, 8, 2, 8)));
		clear.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		clear.setToolTipText("Remove everything from the checklist");
		clear.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (JOptionPane.showConfirmDialog(MaterialChecklistPanel.this,
					"Clear the entire checklist?", "Material Checklist",
					JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)
				{
					plugin.clearChecklist();
				}
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				clear.setForeground(Color.WHITE);
				clear.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				clear.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				clear.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});

		JLabel settings = new JLabel("⚙");
		settings.setFont(FontManager.getDefaultFont().deriveFont(16f));
		settings.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		settings.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		settings.setToolTipText("Open the plugin settings");
		settings.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 6));
		settings.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				plugin.openConfiguration();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				settings.setForeground(ColorScheme.BRAND_ORANGE);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				settings.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			}
		});

		JPanel actions = new JPanel();
		actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
		actions.setOpaque(false);
		actions.add(settings);
		actions.add(clear);

		JPanel titleRow = new JPanel(new BorderLayout());
		titleRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		titleRow.add(title, BorderLayout.WEST);
		titleRow.add(actions, BorderLayout.EAST);

		searchBar.setIcon(IconTextField.Icon.SEARCH);
		searchBar.setPreferredSize(new Dimension(100, 30));
		searchBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchBar.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		searchBar.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				updateSearch();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				updateSearch();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				updateSearch();
			}
		});
		searchBar.addClearListener(this::updateSearch);

		quantitySpinner.setPreferredSize(new Dimension(52, 30));
		quantitySpinner.setToolTipText("How many to add");

		JPanel searchRow = new JPanel(new BorderLayout(4, 0));
		searchRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		searchRow.add(searchBar, BorderLayout.CENTER);
		searchRow.add(quantitySpinner, BorderLayout.EAST);

		header.add(titleRow, BorderLayout.NORTH);
		header.add(searchRow, BorderLayout.CENTER);
		return header;
	}

	private JPanel buildMainCard()
	{
		JPanel display = new JPanel(new BorderLayout());
		display.setBackground(ColorScheme.DARK_GRAY_COLOR);

		MaterialTabGroup tabGroup = new MaterialTabGroup(display);
		MaterialTab materialsTab = new MaterialTab("Materials", tabGroup, wrapScrollable(materialsList, buildMaterialsFooter()));
		MaterialTab goodsTab = new MaterialTab("Goods", tabGroup, wrapScrollable(goodsList, null));
		tabGroup.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
		tabGroup.addTab(materialsTab);
		tabGroup.addTab(goodsTab);
		tabGroup.select(materialsTab);

		JPanel main = new JPanel(new BorderLayout());
		main.setBackground(ColorScheme.DARK_GRAY_COLOR);
		main.add(tabGroup, BorderLayout.NORTH);
		main.add(display, BorderLayout.CENTER);
		return main;
	}

	private JPanel buildMaterialsFooter()
	{
		costLabel.setFont(FontManager.getRunescapeSmallFont());
		costLabel.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
		costLabel.setHorizontalAlignment(SwingConstants.CENTER);

		bankNoteLabel.setFont(FontManager.getRunescapeSmallFont());
		bankNoteLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		bankNoteLabel.setHorizontalAlignment(SwingConstants.CENTER);

		JPanel footer = new JPanel(new DynamicGridLayout(0, 1, 0, 2));
		footer.setBackground(ColorScheme.DARK_GRAY_COLOR);
		footer.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		footer.add(bankNoteLabel);
		footer.add(costLabel);
		return footer;
	}

	private JPanel buildSearchCard()
	{
		searchResultsList.setBackground(ColorScheme.DARK_GRAY_COLOR);
		return wrapScrollable(searchResultsList, null);
	}

	/**
	 * The scroll pane's view must track the viewport width, or rows keep
	 * their preferred width and clip on the right once the vertical
	 * scrollbar appears.
	 */
	private static class ScrollableWidthPanel extends JPanel implements javax.swing.Scrollable
	{
		ScrollableWidthPanel(java.awt.LayoutManager layout)
		{
			super(layout);
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction)
		{
			return 64;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}

	/** Puts a row list into a top-anchored scroll pane, with an optional footer. */
	private JPanel wrapScrollable(JPanel list, JPanel footer)
	{
		list.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JPanel north = new ScrollableWidthPanel(new BorderLayout());
		north.setBackground(ColorScheme.DARK_GRAY_COLOR);
		north.add(list, BorderLayout.NORTH);

		JScrollPane scroll = new JScrollPane(north);
		scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.setBorder(BorderFactory.createEmptyBorder());

		JPanel container = new JPanel(new BorderLayout());
		container.setBackground(ColorScheme.DARK_GRAY_COLOR);
		container.add(scroll, BorderLayout.CENTER);
		if (footer != null)
		{
			container.add(footer, BorderLayout.SOUTH);
		}
		return container;
	}

	// ---- search ----

	private void updateSearch()
	{
		String query = searchBar.getText();
		if (query == null || query.trim().isEmpty())
		{
			cardLayout.show(cards, CARD_MAIN);
			return;
		}
		SwingUtil.fastRemoveAll(searchResultsList);
		for (Recipe recipe : recipeBook.search(query, SEARCH_LIMIT))
		{
			searchResultsList.add(buildSearchRow(recipe));
		}
		if (searchResultsList.getComponentCount() == 0)
		{
			JLabel none = new JLabel("No matching recipes");
			none.setFont(FontManager.getRunescapeSmallFont());
			none.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			none.setHorizontalAlignment(SwingConstants.CENTER);
			none.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
			searchResultsList.add(none);
		}
		searchResultsList.revalidate();
		searchResultsList.repaint();
		cardLayout.show(cards, CARD_SEARCH);
	}

	private JPanel buildSearchRow(Recipe recipe)
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(36, 32));
		if (recipe.iconItemId() > 0)
		{
			itemManager.getImage(recipe.iconItemId()).addTo(icon);
		}

		JLabel name = new JLabel(wrap(recipe.name, 138));
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(Color.WHITE);
		// tooltip goes on the row, not the label: setToolTipText registers a
		// mouse listener that would swallow clicks meant for the row
		row.setToolTipText(recipe.name + requirementText(recipe));

		row.add(icon, BorderLayout.WEST);
		row.add(name, BorderLayout.CENTER);

		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				try
				{
					quantitySpinner.commitEdit();
				}
				catch (java.text.ParseException ignored)
				{
					// keep the last committed value on unparseable input
				}
				plugin.addGoal(recipe.name, (Integer) quantitySpinner.getValue());
				searchBar.setText("");
				cardLayout.show(cards, CARD_MAIN);
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});
		return row;
	}

	private static String requirementText(Recipe recipe)
	{
		if (recipe.skill == null || recipe.skill.isEmpty() || recipe.level <= 0)
		{
			return "";
		}
		return " — " + recipe.skill + " " + recipe.level;
	}

	// ---- rendering ----

	void render(ChecklistSnapshot newSnapshot)
	{
		snapshot = newSnapshot;

		SwingUtil.fastRemoveAll(goodsList);
		if (snapshot.goals.isEmpty())
		{
			goodsList.add(buildEmptyState());
		}
		else
		{
			for (GoalLine line : snapshot.goals)
			{
				addGoalRows(line, 0);
			}
		}
		goodsList.revalidate();
		goodsList.repaint();

		SwingUtil.fastRemoveAll(materialsList);
		for (MaterialLine line : snapshot.totals)
		{
			materialsList.add(buildTotalRow(line));
		}
		if (snapshot.totals.isEmpty() && !snapshot.goals.isEmpty())
		{
			JLabel done = new JLabel("Nothing left to gather!");
			done.setFont(FontManager.getRunescapeSmallFont());
			done.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
			done.setHorizontalAlignment(SwingConstants.CENTER);
			materialsList.add(done);
		}
		materialsList.revalidate();
		materialsList.repaint();

		bankNoteLabel.setText(snapshot.hasBankSnapshot ? "" : "Bank not scanned yet — open your bank once.");
		bankNoteLabel.setVisible(!snapshot.hasBankSnapshot);
		costLabel.setText(snapshot.missingCost > 0
			? "Missing materials: ~" + QuantityFormatter.quantityToStackSize(snapshot.missingCost) + " gp"
			: "");
		costLabel.setVisible(snapshot.missingCost > 0);
	}

	private void addGoalRows(GoalLine line, int depth)
	{
		goodsList.add(buildGoalHeader(line, depth));
		if (line.collapsed || line.recipe == null)
		{
			return;
		}
		addMaterialRows(line, depth + 1);
	}

	private void addMaterialRows(GoalLine line, int depth)
	{
		for (MaterialRow row : line.rows)
		{
			goodsList.add(buildMaterialRow(row, depth));
			if (row.expanded())
			{
				addMaterialRows(row.child, depth + 1);
			}
		}
	}

	private JPanel buildGoalHeader(GoalLine line, int depth)
	{
		JPanel header = new JPanel(new BorderLayout(4, 0));
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		header.setBorder(BorderFactory.createEmptyBorder(2, 4 + depth * INDENT_PER_LEVEL, 2, 4));

		// default (Dialog) font: the bundled RuneScape font has no arrow glyphs
		JLabel arrow = new JLabel(line.collapsed ? "▸" : "▾");
		arrow.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		arrow.setFont(FontManager.getDefaultFont().deriveFont(11f));

		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(36, 32));
		if (line.iconItemId > 0)
		{
			itemManager.getImage(line.iconItemId).addTo(icon);
		}

		JLabel name = new JLabel(wrap(line.goal.name, 68));
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(line.recipe == null ? ColorScheme.PROGRESS_ERROR_COLOR : Color.WHITE);
		StringBuilder tooltip = new StringBuilder("<html><body style='width:180px'><b>")
			.append(escapeHtml(line.goal.name)).append("</b>");
		if (line.recipe == null)
		{
			tooltip.append("<br>Recipe no longer in the dataset — right-click to remove");
		}
		else
		{
			if (line.recipe.skill != null && !line.recipe.skill.isEmpty() && line.recipe.level > 0)
			{
				tooltip.append("<br>Level: ").append(escapeHtml(line.recipe.skill))
					.append(" ").append(line.recipe.level);
			}
			if (line.owned > 0)
			{
				tooltip.append("<br>Already own: ").append(line.owned);
			}
			if (!line.toolsText.isEmpty())
			{
				tooltip.append("<br>Requires: ").append(escapeHtml(line.toolsText));
			}
		}
		tooltip.append("</body></html>");
		header.setToolTipText(tooltip.toString());

		Color countColor = line.owned >= line.units && line.units > 0
			? ColorScheme.PROGRESS_COMPLETE_COLOR
			: ColorScheme.LIGHT_GRAY_COLOR;

		JPanel left = new JPanel(new BorderLayout(4, 0));
		left.setOpaque(false);
		left.add(arrow, BorderLayout.WEST);
		left.add(icon, BorderLayout.EAST);

		header.add(left, BorderLayout.WEST);
		header.add(name, BorderLayout.CENTER);
		if (depth == 0)
		{
			// inline stepper: -/+ click ±1, shift-click ±10; the number itself is editable
			JTextField quantityField = new JTextField(String.valueOf(line.goal.quantity));
			quantityField.setFont(FontManager.getRunescapeSmallFont());
			quantityField.setForeground(countColor);
			quantityField.setBackground(ColorScheme.DARK_GRAY_COLOR);
			quantityField.setCaretColor(Color.WHITE);
			quantityField.setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 2));
			quantityField.setHorizontalAlignment(JTextField.CENTER);
			quantityField.setPreferredSize(new Dimension(34, 18));
			quantityField.setMaximumSize(new Dimension(44, 20));
			quantityField.setToolTipText("Type a quantity and press Enter");
			Runnable commit = () ->
			{
				try
				{
					int quantity = Integer.parseInt(quantityField.getText().trim());
					if (quantity > 0 && quantity != line.goal.quantity)
					{
						plugin.setGoalQuantity(line.goal, quantity);
						return;
					}
				}
				catch (NumberFormatException ignored)
				{
					// fall through and restore the current value
				}
				quantityField.setText(String.valueOf(line.goal.quantity));
			};
			quantityField.addActionListener(e -> commit.run());
			quantityField.addFocusListener(new FocusAdapter()
			{
				@Override
				public void focusLost(FocusEvent e)
				{
					commit.run();
				}
			});

			JPanel stepper = new JPanel();
			stepper.setLayout(new BoxLayout(stepper, BoxLayout.X_AXIS));
			stepper.setOpaque(false);
			stepper.add(quantityButton("-", "Remove one (shift: ten)", line, -1));
			stepper.add(Box.createHorizontalStrut(2));
			stepper.add(quantityField);
			stepper.add(Box.createHorizontalStrut(2));
			stepper.add(quantityButton("+", "Add one (shift: ten)", line, 1));
			header.add(stepper, BorderLayout.EAST);
		}
		else
		{
			JLabel count = new JLabel(String.valueOf(line.units));
			count.setFont(FontManager.getRunescapeSmallFont());
			count.setForeground(countColor);
			header.add(count, BorderLayout.EAST);
		}

		JPopupMenu popup = new JPopupMenu();
		if (depth == 0)
		{
			JMenuItem remove = new JMenuItem("Remove");
			remove.addActionListener(e -> plugin.removeGoal(line.goal));
			popup.add(remove);
		}
		if (line.recipe != null)
		{
			java.util.List<Recipe> variants = recipeBook.variantsFor(line.recipe);
			if (variants.size() > 1)
			{
				JMenu methodMenu = new JMenu("Crafting method");
				for (Recipe variant : variants)
				{
					JRadioButtonMenuItem item = new JRadioButtonMenuItem(
						variant.name + requirementText(variant),
						variant.name.equalsIgnoreCase(line.goal.name));
					if (!item.isSelected())
					{
						item.addActionListener(e -> plugin.changeGoalMethod(line.goal, variant));
					}
					methodMenu.add(item);
				}
				popup.add(methodMenu);
			}
			JMenuItem wiki = new JMenuItem("Open wiki");
			wiki.addActionListener(e -> openWiki(line.recipe.productId, line.goal.name));
			popup.add(wiki);
		}

		header.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					popup.show(header, e.getX(), e.getY());
				}
				else if (e.getButton() == MouseEvent.BUTTON1)
				{
					plugin.toggleCollapsed(line.goal);
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					popup.show(header, e.getX(), e.getY());
				}
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				header.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});
		return header;
	}

	/** A small clickable +/- label adjusting a root goal's quantity. */
	private JLabel quantityButton(String text, String tooltip, GoalLine line, int direction)
	{
		JLabel button = new JLabel(text);
		button.setFont(FontManager.getRunescapeBoldFont());
		button.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		button.setBorder(BorderFactory.createEmptyBorder(0, 3, 0, 3));
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		button.setToolTipText(tooltip);
		button.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (e.getButton() != MouseEvent.BUTTON1)
				{
					return;
				}
				int step = (e.isShiftDown() ? 10 : 1) * direction;
				int quantity = Math.max(1, line.goal.quantity + step);
				plugin.setGoalQuantity(line.goal, quantity);
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				button.setForeground(ColorScheme.BRAND_ORANGE);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				button.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			}
		});
		return button;
	}

	private JPanel buildMaterialRow(MaterialRow row, int depth)
	{
		MaterialLine line = row.line;
		JPanel panel = new JPanel(new BorderLayout(4, 0));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(1, 4 + depth * INDENT_PER_LEVEL, 1, 4));

		String arrowText = row.expanded() ? "▾" : (line.craftable ? "▸" : " ");
		JLabel arrow = new JLabel(arrowText);
		arrow.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		arrow.setFont(FontManager.getDefaultFont().deriveFont(11f));
		arrow.setPreferredSize(new Dimension(12, 16));

		// item sprites are 36x32; a smaller label crops their edges
		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(36, 32));
		itemManager.getImage(line.itemId).addTo(icon);

		boolean nothingNeeded = line.needed == 0;

		JLabel name = new JLabel(wrap(line.name, Math.max(45, 76 - depth * INDENT_PER_LEVEL)));
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(nothingNeeded ? ColorScheme.LIGHT_GRAY_COLOR : Color.WHITE);

		// a fully-covered goal needs none of this ingredient — "11 / 0" reads
		// as a bug, so show a quiet "done" instead
		JLabel count = new JLabel(nothingNeeded
			? "done"
			: QuantityFormatter.quantityToStackSize(line.have())
				+ " / " + QuantityFormatter.quantityToStackSize(line.needed));
		count.setFont(FontManager.getRunescapeSmallFont());
		count.setForeground(nothingNeeded ? ColorScheme.PROGRESS_COMPLETE_COLOR : colorFor(line));

		// tooltip on the row, not the labels — label tooltips swallow row clicks
		String hint = row.expanded()
			? "Click to collapse its recipe"
			: (line.craftable ? "Click to view its recipe" : null);
		panel.setToolTipText(tooltipFor(line, hint));

		JPanel left = new JPanel(new BorderLayout(2, 0));
		left.setOpaque(false);
		left.add(arrow, BorderLayout.WEST);
		left.add(icon, BorderLayout.EAST);

		panel.add(left, BorderLayout.WEST);
		panel.add(name, BorderLayout.CENTER);
		panel.add(count, BorderLayout.EAST);

		JPopupMenu popup = new JPopupMenu();
		if (line.craftable)
		{
			JMenuItem addGoal = new JMenuItem("Add as its own goal");
			addGoal.addActionListener(e -> plugin.addMaterialAsGoal(line.itemId, Math.max(1, line.missing())));
			popup.add(addGoal);
		}
		JMenuItem wiki = new JMenuItem("Open wiki");
		wiki.addActionListener(e -> openWiki(line.itemId, line.name));
		popup.add(wiki);

		panel.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					popup.show(panel, e.getX(), e.getY());
				}
				else if (e.getButton() == MouseEvent.BUTTON1)
				{
					if (row.expanded())
					{
						plugin.collapseMaterial(row.parentGoal, row.child.goal.name);
					}
					else if (line.craftable)
					{
						plugin.expandMaterial(row.parentGoal, line.itemId);
					}
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					popup.show(panel, e.getX(), e.getY());
				}
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				panel.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
			}
		});
		return panel;
	}

	private JPanel buildTotalRow(MaterialLine line)
	{
		JPanel panel = new JPanel(new BorderLayout(4, 0));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(36, 32));
		itemManager.getImage(line.itemId, line.needed, line.needed > 1).addTo(icon);

		JLabel name = new JLabel(wrap(line.name, 88));
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(Color.WHITE);

		JLabel count = new JLabel(QuantityFormatter.quantityToStackSize(line.have())
			+ " / " + QuantityFormatter.quantityToStackSize(line.needed));
		count.setFont(FontManager.getRunescapeSmallFont());
		count.setForeground(colorFor(line));

		panel.setToolTipText(tooltipFor(line,
			line.craftable ? "Right-click to add as its own goal" : null));

		panel.add(icon, BorderLayout.WEST);
		panel.add(name, BorderLayout.CENTER);
		panel.add(count, BorderLayout.EAST);

		JPopupMenu popup = new JPopupMenu();
		if (line.craftable)
		{
			JMenuItem addGoal = new JMenuItem("Add as its own goal");
			addGoal.addActionListener(e -> plugin.addMaterialAsGoal(line.itemId, Math.max(1, line.missing())));
			popup.add(addGoal);
		}
		JMenuItem wiki = new JMenuItem("Open wiki");
		wiki.addActionListener(e -> openWiki(line.itemId, line.name));
		popup.add(wiki);
		panel.setComponentPopupMenu(popup);
		return panel;
	}

	private String tooltipFor(MaterialLine line, String hint)
	{
		StringBuilder sb = new StringBuilder("<html><b>").append(line.name).append("</b><br>");
		sb.append("Inventory: ").append(QuantityFormatter.quantityToStackSize(line.inventory));
		sb.append(" &nbsp; Bank: ").append(QuantityFormatter.quantityToStackSize(line.bank));
		sb.append("<br>Needed: ").append(QuantityFormatter.quantityToStackSize(line.needed));
		if (line.missing() > 0)
		{
			sb.append(" &nbsp; Missing: ").append(QuantityFormatter.quantityToStackSize(line.missing()));
			if (line.missingCost > 0)
			{
				sb.append(" (~").append(QuantityFormatter.quantityToStackSize(line.missingCost)).append(" gp)");
			}
		}
		if (hint != null)
		{
			sb.append("<br>").append(hint);
		}
		sb.append("</html>");
		return sb.toString();
	}

	/**
	 * Built fresh per render (fastRemoveAll guts reused components). The stock
	 * PluginErrorPanel sizes its text as one long line and overflows our
	 * scroll area, so this is a width-constrained replacement.
	 */
	private JPanel buildEmptyState()
	{
		JPanel empty = new JPanel(new DynamicGridLayout(0, 1, 0, 6));
		empty.setBackground(ColorScheme.DARK_GRAY_COLOR);
		empty.setBorder(BorderFactory.createEmptyBorder(16, 8, 8, 8));

		JLabel title = new JLabel("Material Checklist", SwingConstants.CENTER);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);

		JLabel description = new JLabel(
			"<html><body style='width:165px;text-align:center'>Search for an item to craft above, "
				+ "or right-click an entry in a skill guide.</body></html>",
			SwingConstants.CENTER);
		description.setFont(FontManager.getRunescapeSmallFont());
		description.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		empty.add(title);
		empty.add(description);
		return empty;
	}

	private static String escapeHtml(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/** Wraps a name onto multiple lines at the given pixel width (JLabels do not wrap plain text). */
	private static String wrap(String text, int widthPx)
	{
		return "<html><body style='width:" + widthPx + "px'>" + escapeHtml(text) + "</body></html>";
	}

	private static Color colorFor(MaterialLine line)
	{
		if (line.inventory >= line.needed)
		{
			return ColorScheme.PROGRESS_COMPLETE_COLOR;
		}
		if (line.have() >= line.needed)
		{
			return Color.WHITE;
		}
		if (line.craftable)
		{
			return ColorScheme.PROGRESS_INPROGRESS_COLOR;
		}
		return ColorScheme.PROGRESS_ERROR_COLOR;
	}

	private static void openWiki(int itemId, String name)
	{
		String url;
		if (itemId > 0)
		{
			url = "https://oldschool.runescape.wiki/w/Special:Lookup?type=item&id=" + itemId;
		}
		else
		{
			try
			{
				url = "https://oldschool.runescape.wiki/w/Special:Search?search="
					+ URLEncoder.encode(name, "UTF-8");
			}
			catch (UnsupportedEncodingException e)
			{
				return;
			}
		}
		LinkBrowser.browse(url);
	}
}
