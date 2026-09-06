# Research: panel-ui

## RuneLite side-panel UI construction â€” verified against runelite/runelite master (fetched 2026-09-05)

### 1. PluginPanel (`net.runelite.client.ui.PluginPanel`)
`public abstract class PluginPanel extends JPanel implements Activatable`. Constants: `PANEL_WIDTH = 225`, `SCROLLBAR_WIDTH = 17`, `BORDER_OFFSET = 6`. Two constructors: `protected PluginPanel()` (delegates to `this(true)`) and `protected PluginPanel(boolean wrap)`. With `wrap=true` the panel gets a 6px `EmptyBorder`, a `DynamicGridLayout(0, 1, 0, 3)` (vertical auto-grid, `net.runelite.client.ui.DynamicGridLayout`), `ColorScheme.DARK_GRAY_COLOR` background, and is embedded in a north-anchored inner panel inside a `JScrollPane` with `HORIZONTAL_SCROLLBAR_NEVER`; the outer `wrappedPanel` (exposed via `@Getter getWrappedPanel()`) has preferred width `PANEL_WIDTH + SCROLLBAR_WIDTH`. Pass `wrap=false` (like GrandExchangePanel does) to manage your own layout/scrolling â€” you then typically `setLayout(new BorderLayout())` yourself. `scrollPane` is protected-getter accessible. Lifecycle hooks come from `net.runelite.client.ui.Activatable`: `default void onActivate() {}` / `default void onDeactivate() {}` â€” called (via `SwingUtil.activate/deactivate`) when the panel is shown/hidden in the sidebar; use them to refresh data lazily.

### 2. NavigationButton + ClientToolbar
`net.runelite.client.ui.NavigationButton` is `@Value @Builder` (Lombok). Builder fields: `icon` (`BufferedImage`), `tooltip` (`String`, `@Builder.Default` ""), `onClick` (`Runnable`), `panel` (`PluginPanel`), `priority` (`int`, lower = higher in sidebar; loot tracker uses 5), `popup` (`Map<String, Runnable>` right-click context menu). Icons are resized by ClientUI to `TAB_SIZE = 16` px via `ImageUtil.resizeImage`, so ship a ~16x16 PNG. `net.runelite.client.ui.ClientToolbar` is `@Singleton` with `addNavigation(NavigationButton)`, `removeNavigation(NavigationButton)`, and `openPanel(NavigationButton)` (programmatically opens/fronts your panel â€” useful for drill-down jumps). Canonical registration (verbatim from LootTrackerPlugin.startUp()):
```java
panel = new LootTrackerPanel(this, itemManager, config);
final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "panel_icon.png");
navButton = NavigationButton.builder()
    .tooltip("Loot Tracker").icon(icon).priority(5).panel(panel).build();
clientToolbar.addNavigation(navButton);
// shutDown():
clientToolbar.removeNavigation(navButton);
```
`ImageUtil.loadImageResource(Class<?> c, String path)` (in `net.runelite.client.util.ImageUtil`) resolves the path relative to the class's package on the classpath â€” put `panel_icon.png` next to the plugin class under `src/main/resources/<package path>/`.

### 3. Threading rules
- `plugin.startUp()`/`shutDown()` run on the Swing EDT â€” PluginManager asserts `SwingUtilities.isEventDispatchThread()`. So constructing Swing panels and calling `clientToolbar.addNavigation` in startUp is correct; do NOT touch game state there directly â€” wrap in `clientThread.invoke(...)`.
- EventBus posting is synchronous: `@Subscribe` handlers for game events (GameTick, ItemContainerChanged, WidgetLoaded, â€¦) run on the **client (game) thread**. Never mutate Swing components there â€” hand off with `SwingUtilities.invokeLater(() -> panel.update(...))` (exact pattern used by LootTrackerPlugin: `SwingUtilities.invokeLater(() -> panel.addRecords(...))` after resolving compositions/prices on the client thread).
- `net.runelite.client.callback.ClientThread`: `invoke(Runnable)` (runs immediately if already on client thread, else queues), `invoke(BooleanSupplier)` (re-runs until it returns true), `invokeLater(Runnable)`, `invokeLater(BooleanSupplier)`, `invokeAtTickEnd(Runnable)`. Use it from Swing listeners to read `client.getItemContainer(...)`, item definitions, etc., then bounce results back to the EDT.
- GrandExchangeSearchPanel's three-phase search is the model for our item-search box: Swing ActionListener â†’ `executor.execute(() -> priceLookup(...))` (background `ScheduledExecutorService`, injectable) â†’ `itemManager.search(text)` (tradeable-item name search, off-thread safe) â†’ `clientThread.invokeLater(() -> processResult(...))` â†’ build result rows inside `SwingUtilities.invokeLater`, fetching icons with `itemManager.getImage(itemId)`.

### 4. ItemManager images and item data (`net.runelite.client.game.ItemManager`)
- `public AsyncBufferedImage getImage(int itemId)` and `public AsyncBufferedImage getImage(int itemId, int quantity, boolean stackable)` â€” javadoc: "may return immediately with a blank image if not called on the game thread. The image will be filled in later. If this is used for a UI label/button, it should be added using AsyncBufferedImage::addTo to ensure it is painted properly."
- `net.runelite.client.util.AsyncBufferedImage extends BufferedImage`: `addTo(JLabel)`, `addTo(JButton)` (sets an ImageIcon and repaints the component when the async load completes), `onLoaded(Runnable)`, `loaded()`. Loot tracker usage (LootTrackerBox): `AsyncBufferedImage itemImage = itemManager.getImage(item.getId(), item.getQuantity(), item.getQuantity() > 1); itemImage.addTo(imageLabel);` with a `GridLayout(rows, ITEMS_PER_ROW /*=5*/, 1, 1)` of `JLabel`s and `imageLabel.setToolTipText(buildToolTip(item))`.
- Other useful methods: `@Nonnull ItemComposition getItemComposition(int itemId)` (now a thin call to `client.getItemDefinition`; no thread requirement stated in current javadoc, but core plugins still resolve on the client thread), `int canonicalize(int itemID)` ("un-noted, un-placeholdered ID" â€” essential when counting bank/inventory materials), `List<ItemPrice> search(String itemName)`, `int getItemPrice(int itemID)`, `getItemPriceWithSource(int, boolean)`, `getWikiPrice(ItemPrice)`, `BufferedImage getItemOutline(int itemId, int qty, Color outlineColor)`.
- `Client.getItemContainer` now has overloads `getItemContainer(InventoryID)` (enum) and `getItemContainer(int id)` referencing `net.runelite.api.gameval.InventoryID` â€” the gameval int-ID path is the current direction.

### 5. Bundled components (`net.runelite.client.ui.components`)
Package contents: `IconTextField`, `FlatTextField`, `ProgressBar`, `ThinProgressBar`, `PluginErrorPanel`, `DragAndDropReorderPane`, `ColorJButton`, `DimmableJPanel`, `MouseDragEventForwarder`, `TitleCaseListCellRenderer`, plus subpackages `materialtabs`, `shadowlabel`, `colorpicker`.
- **IconTextField** (`extends JPanel`): `setIcon(IconTextField.Icon)` with enum `SEARCH/LOADING/LOADING_DARKER/ERROR`, `addActionListener`, `addClearListener(Runnable)`, `addKeyListener`, `setText/getText`, `setPlaceholder`-like via document access `getDocument()`, `setEditable`, `setHoverBackgroundColor`, `getSuggestionListModel()` (built-in suggestion dropdown). Quest Helper pattern: `searchBar.setIcon(IconTextField.Icon.SEARCH); searchBar.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 30)); searchBar.getDocument().addDocumentListener(new DocumentListener() {...})` for live filtering.
- **ProgressBar** (`extends DimmableJPanel`): `setMaximumValue(int)`, `setValue(int)`, `setLeftLabel/setRightLabel/setCenterLabel(String)`, `setPositions(List<Integer>)`, `setDimmed(boolean)`, `getPercentage()`; uses `FontManager.getRunescapeSmallFont()`, 100x16 preferred size. Ideal for "owned/needed" per-material progress.
- **MaterialTabGroup / MaterialTab** (`net.runelite.client.ui.components.materialtabs`): `new MaterialTabGroup(JPanel display)`; `MaterialTab(String text, MaterialTabGroup group, JComponent content)` or `(ImageIcon icon, group, content)`; `tabGroup.addTab(tab)`, `tabGroup.select(tab)`, `tab.setOnSelectEvent(BooleanSupplier)`. Selecting swaps the tab's content into the display panel (removeAll/add/revalidate/repaint). GrandExchangePanel is the exact two-tab template for our Raw Materials / Finished Goods views:
```java
MaterialTabGroup tabGroup = new MaterialTabGroup(display);
MaterialTab offersTab = new MaterialTab("Offers", tabGroup, offersPanel);
searchTab = new MaterialTab("Search", tabGroup, searchPanel);
tabGroup.addTab(offersTab); tabGroup.addTab(searchTab); tabGroup.select(offersTab);
add(tabGroup, BorderLayout.NORTH); add(display, BorderLayout.CENTER);
```
Selected tab styling: 1px `BRAND_ORANGE` bottom border, white text; unselected gray.
- **JShadowedLabel** (`net.runelite.client.ui.components.shadowlabel.JShadowedLabel extends JLabel`): constructors `()`/`(String)`, `setShadow(Color)`, `setShadowSize(Point)`.
- **PluginErrorPanel**: `setContent(String title, String description)` â€” centered white title + gray HTML-wrapped description; the standard empty-state widget ("You have not received any loot yet." in loot tracker).
- **SwingUtil** (`net.runelite.client.util.SwingUtil`): `removeButtonDecorations(AbstractButton)`, `addModalTooltip(AbstractButton, String on, String off)`, `fastRemoveAll(Container)` (much faster than removeAll for big lists), `activate/deactivate(Object)`.

### 6. ColorScheme & FontManager (`net.runelite.client.ui`)
All ColorScheme constants (verbatim): `BRAND_ORANGE (220,138,0)`, `BRAND_ORANGE_TRANSPARENT (220,138,0,120)`, `DARKER_GRAY_COLOR (30,30,30)`, `DARK_GRAY_COLOR (40,40,40)`, `MEDIUM_GRAY_COLOR (77,77,77)`, `LIGHT_GRAY_COLOR (165,165,165)`, `TEXT_COLOR (198,198,198)`, `CONTROL_COLOR (30,30,30)`, `BORDER_COLOR (23,23,23)`, `DARKER_GRAY_HOVER_COLOR (60,60,60)`, `DARK_GRAY_HOVER_COLOR (35,35,35)`, `PROGRESS_COMPLETE_COLOR (55,240,70)`, `PROGRESS_ERROR_COLOR (230,30,30)`, `PROGRESS_INPROGRESS_COLOR (230,150,30)`, `GRAND_EXCHANGE_PRICE (110,225,110)`, `GRAND_EXCHANGE_ALCH (240,207,123)`, `GRAND_EXCHANGE_LIMIT (50,160,250)`, `SCROLL_TRACK_COLOR (25,25,25)`. Convention: panel background `DARK_GRAY_COLOR`, card/row background `DARKER_GRAY_COLOR`, hover `DARKER_GRAY_HOVER_COLOR`. FontManager exposes static getters `FontManager.getRunescapeFont()` (plain 16), `getRunescapeBoldFont()`, `getRunescapeSmallFont()`, `getDefaultFont()`/`getDefaultBoldFont()` (Dialog), plus `getFallbackFont(String family, int style, int size)`.

### 7. How real panels are structured
- **LootTrackerPanel** (`class LootTrackerPanel extends PluginPanel`, package-private): root `BorderLayout`, a `layoutPanel` with `BoxLayout(Y_AXIS)` added `BorderLayout.NORTH`, three sections (actions bar / overall summary / `logsContainer` BoxLayout Y for entry boxes). Static icon fields built once: `ImageUtil.loadImageResource(LootTrackerPlugin.class, "single_loot_icon.png")` with hover/faded variants via `ImageUtil.alphaOffset(img, -180)` / `-220`. Empty state via `PluginErrorPanel`.
- **LootTrackerBox** (`class LootTrackerBox extends JPanel`): collapsible per-source box, 5-per-row `GridLayout` of item `JLabel`s with `AsyncBufferedImage.addTo`, tooltips with name/qty/GE/HA.
- **SkillCalculatorPanel** (`class SkillCalculatorPanel extends PluginPanel`, `@Inject` constructor taking `SkillCalculator`, `SkillIconManager`, `UICalculatorInputArea`): `GridBagLayout` with `c.fill = HORIZONTAL; c.weightx = 1; c.gridx = 0; c.gridy = 0` incrementing gridy per section; icon-only `MaterialTab`s built from `iconManager.getSkillImage(skill, true)` (SkillIconManager is injectable â€” gives 25x25 or small skill icons, useful for grouping recipes by skill).
- **Hub plugin (quest-helper QuestHelperPanel)**: extends PluginPanel, BorderLayout root, IconTextField live-search via DocumentListener, `CardLayout` on a viewport panel inside its own JScrollPane for listâ†’detailâ†’settings view switching (`showView(name)`), sections stacked with BoxLayout. CardLayout drill-down (list of finished goods â†’ material detail) is the established hub pattern; loot tracker's SpriteManager header icon load (`spriteManager.getSpriteAsync(SpriteID.SideIcons.INVENTORY, 0, panel::loadHeaderIcon)`) shows the new gameval `SpriteID.SideIcons` constants.

### 8. Build coordinates (example-plugin master)
`compileOnly group: 'net.runelite', name: 'client', version: runeLiteVersion` with `def runeLiteVersion = 'latest.release'`, repo `maven { url = 'https://repo.runelite.net'; content { includeGroupByRegex("net\\.runelite.*") } }` plus mavenCentral; Lombok 1.18.30 compileOnly + annotationProcessor; tests add `net.runelite:client` and `net.runelite:jshell`.

## Key facts
- PluginPanel (net.runelite.client.ui.PluginPanel) is `public abstract class PluginPanel extends JPanel implements Activatable`; constants PANEL_WIDTH=225, SCROLLBAR_WIDTH=17, BORDER_OFFSET=6; constructors PluginPanel() and PluginPanel(boolean wrap); wrap=true gives 6px EmptyBorder + DynamicGridLayout(0,1,0,3) + a JScrollPane wrapper exposed via getWrappedPanel(); pass wrap=false to control your own layout (GrandExchangePanel does this).
- Activatable (net.runelite.client.ui.Activatable) provides default no-op onActivate()/onDeactivate() hooks invoked when the panel is shown/hidden in the sidebar â€” use for lazy refresh.
- NavigationButton (net.runelite.client.ui.NavigationButton) is a Lombok @Value @Builder with fields: BufferedImage icon, String tooltip (@Builder.Default ""), Runnable onClick, PluginPanel panel, int priority (lower = higher in sidebar; loot tracker uses 5), Map<String,Runnable> popup (right-click menu). ClientUI resizes the icon to TAB_SIZE=16px, so ship a ~16x16 panel_icon.png.
- ClientToolbar (net.runelite.client.ui.ClientToolbar, @Singleton, injectable) API: addNavigation(NavigationButton), removeNavigation(NavigationButton), openPanel(NavigationButton) â€” openPanel programmatically opens/fronts your panel (useful for drill-down).
- Icon loading: ImageUtil.loadImageResource(Class<?> c, String path) in net.runelite.client.util.ImageUtil resolves path relative to the class's package on the classpath; the old getResourceStreamFromClass is @Deprecated. Faded/hover icon variants are made with ImageUtil.alphaOffset(img, -180) etc.; other helpers: resizeImage, luminanceOffset, luminanceScale, grayscaleImage, recolorImage(Image, Color), bufferedImageFromImage.
- Plugin.startUp() and shutDown() run on the Swing EDT â€” PluginManager.startPlugin/stopPlugin assert SwingUtilities.isEventDispatchThread(). Building the panel and calling clientToolbar.addNavigation in startUp is correct; game-state reads there must go through clientThread.invoke.
- EventBus posting is synchronous, so @Subscribe handlers for game events (GameTick, ItemContainerChanged, etc.) run on the client thread; update Swing only via SwingUtilities.invokeLater â€” loot tracker does SwingUtilities.invokeLater(() -> panel.addRecords(...)) after resolving item compositions/prices on the client thread.
- ClientThread (net.runelite.client.callback.ClientThread) methods: invoke(Runnable) (immediate if already on client thread), invoke(BooleanSupplier) (re-runs until true), invokeLater(Runnable), invokeLater(BooleanSupplier), invokeAtTickEnd(Runnable).
- ItemManager.getImage overloads: AsyncBufferedImage getImage(int itemId) and getImage(int itemId, int quantity, boolean stackable); javadoc: may return a blank image if called off the game thread, filled in later; for UI labels/buttons the image should be attached with AsyncBufferedImage::addTo so it repaints when loaded.
- AsyncBufferedImage (net.runelite.client.util.AsyncBufferedImage extends BufferedImage): addTo(JLabel), addTo(JButton), onLoaded(Runnable), loaded(). LootTrackerBox pattern: itemManager.getImage(id, qty, qty > 1).addTo(imageLabel) inside a GridLayout(rows, ITEMS_PER_ROW=5, 1, 1) of JLabels with setToolTipText.
- Other ItemManager methods: @Nonnull ItemComposition getItemComposition(int) (now direct client.getItemDefinition call, no cache), int canonicalize(int) (un-noted/un-placeholdered ID â€” needed when counting bank materials), List<ItemPrice> search(String) (tradeable items, safe off-thread), getItemPrice(int), getItemPriceWithSource(int,boolean), getWikiPrice(ItemPrice), getItemOutline(int,int,Color).
- Item search UI pattern (GrandExchangeSearchPanel): IconTextField searchBar with setIcon(IconTextField.Icon.SEARCH), addActionListener(e -> executor.execute(() -> priceLookup(false))), addClearListener; flow = background ScheduledExecutorService -> itemManager.search(text) -> clientThread.invokeLater(processResult) -> SwingUtilities.invokeLater to build rows with itemManager.getImage(itemId).
- IconTextField (net.runelite.client.ui.components.IconTextField extends JPanel): Icon enum SEARCH/LOADING/LOADING_DARKER/ERROR; methods addActionListener, addClearListener(Runnable), addKeyListener, setText/getText, setEditable, setHoverBackgroundColor, getDocument(), getSuggestionListModel() for a built-in suggestion dropdown. Quest Helper sizes it new Dimension(PluginPanel.PANEL_WIDTH - 20, 30) and live-filters via DocumentListener.
- ProgressBar (net.runelite.client.ui.components.ProgressBar extends DimmableJPanel): setMaximumValue(int), setValue(int), setLeftLabel/setRightLabel/setCenterLabel(String), setPositions(List<Integer>), setDimmed(boolean), getPercentage(); FontManager.getRunescapeSmallFont, 100x16 default â€” good for owned-vs-needed material bars.
- MaterialTabGroup/MaterialTab (net.runelite.client.ui.components.materialtabs): new MaterialTabGroup(JPanel display); new MaterialTab(String text | ImageIcon icon, MaterialTabGroup group, JComponent content); addTab, select, tab.setOnSelectEvent(BooleanSupplier); selecting swaps content into display (removeAll/add/revalidate/repaint); selected tab = 1px BRAND_ORANGE bottom border + white text. GrandExchangePanel is the exact two-text-tab template (tabGroup NORTH, display CENTER).
- Empty-state convention: PluginErrorPanel (net.runelite.client.ui.components.PluginErrorPanel) with setContent(String title, String description) â€” white title, gray HTML-wrapped description.
- JShadowedLabel lives at net.runelite.client.ui.components.shadowlabel.JShadowedLabel (extends JLabel) with setShadow(Color)/setShadowSize(Point).
- ColorScheme (net.runelite.client.ui.ColorScheme) key constants: BRAND_ORANGE(220,138,0), DARKER_GRAY_COLOR(30,30,30) for cards/rows, DARK_GRAY_COLOR(40,40,40) for panel background, DARKER_GRAY_HOVER_COLOR(60,60,60), LIGHT_GRAY_COLOR(165,165,165), PROGRESS_COMPLETE_COLOR(55,240,70), PROGRESS_ERROR_COLOR(230,30,30), PROGRESS_INPROGRESS_COLOR(230,150,30), GRAND_EXCHANGE_PRICE(110,225,110), SCROLL_TRACK_COLOR(25,25,25), BORDER_COLOR(23,23,23), MEDIUM_GRAY_COLOR(77,77,77), TEXT_COLOR(198,198,198).
- FontManager (net.runelite.client.ui.FontManager) static getters: getRunescapeFont() (plain 16), getRunescapeBoldFont(), getRunescapeSmallFont(), getDefaultFont(), getDefaultBoldFont(); also getFallbackFont(String family,int style,int size).
- SwingUtil (net.runelite.client.util.SwingUtil): removeButtonDecorations(AbstractButton), addModalTooltip(AbstractButton,String,String), fastRemoveAll(Container) (use when rebuilding long material lists), activate/deactivate(Object).
- Drill-down navigation in a rich hub panel (quest-helper QuestHelperPanel): PluginPanel + BorderLayout root, a CardLayout viewport inside its own JScrollPane, showView(name) to switch list/detail/settings â€” the established pattern for our Finished Goods -> materials drill-down. SkillCalculatorPanel instead uses GridBagLayout (fill=HORIZONTAL, weightx=1, incrementing gridy) with icon-only MaterialTabs from injectable SkillIconManager.getSkillImage(skill, true).
- Loot tracker header icon uses the new gameval sprite constants: spriteManager.getSpriteAsync(SpriteID.SideIcons.INVENTORY, 0, panel::loadHeaderIcon); Client.getItemContainer now has both getItemContainer(InventoryID enum) and getItemContainer(int id) referencing net.runelite.api.gameval.InventoryID.
- Hub build coordinates (example-plugin master): def runeLiteVersion = 'latest.release'; compileOnly 'net.runelite:client'; repo maven { url = 'https://repo.runelite.net'; content { includeGroupByRegex("net\\.runelite.*") } }; Lombok 1.18.30 compileOnly + annotationProcessor; testImplementation net.runelite:client and net.runelite:jshell.

## Code patterns
// --- Panel registration (verbatim, LootTrackerPlugin.startUp, runelite master) ---
panel = new LootTrackerPanel(this, itemManager, config);
spriteManager.getSpriteAsync(SpriteID.SideIcons.INVENTORY, 0, panel::loadHeaderIcon);
final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "panel_icon.png");
navButton = NavigationButton.builder()
	.tooltip("Loot Tracker")
	.icon(icon)
	.priority(5)
	.panel(panel)
	.build();
clientToolbar.addNavigation(navButton);
// shutDown(): clientToolbar.removeNavigation(navButton);

// --- PluginPanel root layout (LootTrackerPanel constructor) ---
setLayout(new BorderLayout());
final JPanel layoutPanel = new JPanel();
layoutPanel.setLayout(new BoxLayout(layoutPanel, BoxLayout.Y_AXIS));
add(layoutPanel, BorderLayout.NORTH);

// --- Item icon in a list row (LootTrackerBox.buildItems) ---
private static final int ITEMS_PER_ROW = 5;
AsyncBufferedImage itemImage = itemManager.getImage(item.getId(), item.getQuantity(), item.getQuantity() > 1);
itemImage.addTo(imageLabel);              // repaints JLabel when async load completes
imageLabel.setToolTipText(buildToolTip(item));
// grid: new GridLayout(rowCount, ITEMS_PER_ROW, 1, 1)

// --- Two-view tab bar (GrandExchangePanel, verbatim) ---
MaterialTabGroup tabGroup = new MaterialTabGroup(display);
MaterialTab offersTab = new MaterialTab("Offers", tabGroup, offersPanel);
searchTab = new MaterialTab("Search", tabGroup, searchPanel);
tabGroup.addTab(offersTab);
tabGroup.addTab(searchTab);
tabGroup.select(offersTab);
add(tabGroup, BorderLayout.NORTH);
add(display, BorderLayout.CENTER);

// --- Item search bar + threading (GrandExchangeSearchPanel) ---
private final IconTextField searchBar = new IconTextField();
searchBar.setIcon(IconTextField.Icon.SEARCH);
searchBar.setPreferredSize(new Dimension(100, 30));
searchBar.addActionListener(e -> executor.execute(() -> priceLookup(false)));  // ScheduledExecutorService (injectable)
searchBar.addClearListener(this::updateSearch);
// bg thread: List<ItemPrice> result = itemManager.search(searchBar.getText());
// then:      clientThread.invokeLater(() -> processResult(result, text, exactMatch));
// then:      SwingUtilities.invokeLater(() -> { ... itemManager.getImage(itemId) per row ... });

// --- Live-filter search bar (quest-helper QuestHelperPanel, hub plugin) ---
searchBar.setIcon(IconTextField.Icon.SEARCH);
searchBar.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 30));
searchBar.getDocument().addDocumentListener(new DocumentListener() {
	@Override public void insertUpdate(DocumentEvent e) { onSearchBarChanged(); }
	@Override public void removeUpdate(DocumentEvent e) { onSearchBarChanged(); }
	@Override public void changedUpdate(DocumentEvent e) { onSearchBarChanged(); }
});

// --- Faded/hover icon variants (LootTrackerPanel statics) ---
final BufferedImage backArrowImg = ImageUtil.loadImageResource(LootTrackerPlugin.class, "back_icon.png");
BACK_ARROW_ICON_HOVER = new ImageIcon(ImageUtil.alphaOffset(backArrowImg, -180));

// --- Empty state ---
private final PluginErrorPanel errorPanel = new PluginErrorPanel();
errorPanel.setContent("Loot tracker", "You have not received any loot yet.");

// --- Key signatures (verbatim from master) ---
// net.runelite.client.ui.ClientToolbar: public void addNavigation(NavigationButton button); public void removeNavigation(final NavigationButton button); public void openPanel(NavigationButton button)
// net.runelite.client.game.ItemManager: public AsyncBufferedImage getImage(int itemId); public AsyncBufferedImage getImage(int itemId, int quantity, boolean stackable); @Nonnull public ItemComposition getItemComposition(int itemId); public int canonicalize(int itemID); public List<ItemPrice> search(String itemName); public BufferedImage getItemOutline(final int itemId, final int itemQuantity, final Color outlineColor)
// net.runelite.client.callback.ClientThread: public void invoke(Runnable r); public void invoke(BooleanSupplier r); public void invokeLater(Runnable r); public void invokeLater(BooleanSupplier r); public void invokeAtTickEnd(Runnable r)
// net.runelite.client.util.ImageUtil: public static BufferedImage loadImageResource(final Class<?> c, final String path); public static BufferedImage resizeImage(final BufferedImage image, final int newWidth, final int newHeight); public static BufferedImage alphaOffset(final Image rawImg, final int offset)
// net.runelite.client.util.AsyncBufferedImage: public AsyncBufferedImage(ClientThread clientThread, int width, int height, int imageType); public synchronized void onLoaded(Runnable r); public void addTo(JLabel c); public void addTo(JButton c)
// net.runelite.client.ui.components.ProgressBar: public void setMaximumValue(int maximumValue); public void setValue(int value); public void setLeftLabel(String txt); public void setRightLabel(String txt); public void setCenterLabel(String txt); public void setPositions(List<Integer> positions); public void setDimmed(boolean dimmed)

## Risks
- WebFetch summarization: signatures above were extracted from raw master files by an intermediate model; the shapes are consistent across files and with known API, but exact modifier/annotation details on rarely-used members (e.g. IconTextField suggestion API) should be confirmed against the compile when coding.
- FontManager appears to have been reworked recently (custom/system font support, getBuiltInFonts/getSystemFonts/getFallbackFont, sizes driven by config with default 16) â€” do not hardcode assumptions about pixel sizes; keep using the static getRunescape*Font getters, which remain.
- ItemManager.getItemComposition no longer documents a client-thread requirement (it delegates straight to client.getItemDefinition and the composition cache was removed), but core plugins still resolve compositions/prices on the client thread before touching Swing; safest to follow that pattern rather than calling from the EDT.
- The api InventoryID enum coexists with net.runelite.api.gameval.InventoryID int constants; the enum-based getItemContainer overload may be deprecated in the future â€” prefer the int/gameval overload for new code (another research dimension covers this in depth).
- NavigationButton icons are force-resized to 16x16 in ClientUI; supplying a larger icon works but will be scaled â€” provide a purpose-drawn 16px PNG for crispness.
- PluginPanel wrap=true gives DynamicGridLayout with a built-in scroll wrapper; mixing that with your own JScrollPane causes double-scrollbars â€” rich panels (GE, quest-helper) use super(false)/their own scrolling. Decide the wrap mode up front.
- MaterialTabGroup.select() calls removeAll on the display panel; heavy content panels should be retained components (not rebuilt per selection) and long list rebuilds should use SwingUtil.fastRemoveAll to avoid EDT stalls.
- AsyncBufferedImage.getImage called off the game thread returns a blank image filled in later â€” always attach via addTo(JLabel/JButton) (or onLoaded) or icons will intermittently render empty in the panel.

## Sources
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-client/src/main/java/net/runelite/client/ui/PluginPanel.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-client/src/main/java/net/runelite/client/ui/NavigationButton.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-client/src/main/java/net/runelite/client/ui/ClientToolbar.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-client/src/main/java/net/runelite/client/ui/Activatable.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-client/src/main/java/net/runelite/client/ui/ClientUI.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-client/src/main/java/net/runelite/client/ui/ColorScheme.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-client/src/main/java/net/runelite/client/ui/FontManager.java
- https://api.github.com/repos/runelite/runelite/contents/runelite-client/src/main/java/net/runelite/client/ui/components
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-client/src/main/java/net/runelite/client/ui/components/IconTextField.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-client/src/main/java/net/runelite/client/ui/components/ProgressBar.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-client/src/main/java/net/runelite/client/ui/components/PluginErrorPanel.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-client/src/main/java/net/runelite/client/ui/components/materialtabs/MaterialTabGroup.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-client/src/main/java/net/runelite/client/ui/components/materialtabs/MaterialTab.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-client/src/main/java/net/runelite/client/ui/components/shadowlabel/JShadowedLabel.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-client/src/main/java/net/runelite/client/util/ImageUtil.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-client/src/main/java/net/runelite/client/util/AsyncBufferedImage.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-client/src/main/java/net/runelite/client/util/SwingUtil.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-client/src/main/java/net/runelite/client/game/ItemManager.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-client/src/main/java/net/runelite/client/callback/ClientThread.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-client/src/main/java/net/runelite/client/plugins/PluginManager.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-client/src/main/java/net/runelite/client/plugins/loottracker/LootTrackerPlugin.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-client/src/main/java/net/runelite/client/plugins/loottracker/LootTrackerPanel.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-client/src/main/java/net/runelite/client/plugins/loottracker/LootTrackerBox.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-client/src/main/java/net/runelite/client/plugins/skillcalculator/SkillCalculatorPanel.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-client/src/main/java/net/runelite/client/plugins/grandexchange/GrandExchangePanel.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-client/src/main/java/net/runelite/client/plugins/grandexchange/GrandExchangeSearchPanel.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-api/src/main/java/net/runelite/api/Client.java
- https://raw.githubusercontent.com/Zoinkwiz/quest-helper/master/src/main/java/com/questhelper/panel/QuestHelperPanel.java
- https://raw.githubusercontent.com/runelite/example-plugin/master/build.gradle
- https://static.runelite.net/runelite-client/apidocs/net/runelite/client/eventbus/EventBus.html
