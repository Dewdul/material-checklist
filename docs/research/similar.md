# Research: similar

# Genre survey: material/checklist/goal plugins on the RuneLite Plugin Hub (verified 2026-09-05)

## Direct-overlap verdict
**A plugin substantially identical to "Material Checklist" already exists: "Recipe Checklist"** (hub slug `recipe-checklist`, repo `KludensVogter/Recipes_Checklist`, v1.1.0, 22 installs). Feature-for-feature it is the planned plugin: search-and-add products with quantity, per-goal collapsible material list with `have / need` counts, counts BOTH inventory and bank (persistent per-account bank snapshot), an aggregated "Materials needed" shopping-list summary at top, **recursive sub-recipes** (clicking a craftable material adds its recipe as a child goal and the summary substitutes its raw inputs), color coding (green=in inventory, white=in bank, yellow=can make from stock, red=short), persistence across sessions, in-game add via skill guide/Construction/Shipwright clicks, and a ~4,600-recipe `recipes.json` generated from the OSRS Wiki. A second near-identical plugin, **"Recipe Tracker"** (`nassarao/recipe-tracker`, 193 installs), has the same core loop (bundled wiki recipe index + live wiki fallback, recursive sub-recipe expansion, combined shopping list replacing intermediates with raw inputs, green completed rows, persistence, plus an in-game overlay). A third, **"Required Materials"** (`SchauweM/runelite-required-materials`, 77 installs), does the same for Sailing+Construction only with live wiki fetching. All three are low-install; the niche is occupied but not dominated (compare Banked Experience 247,666 installs). Building "Material Checklist" would be a re-implementation, not a gap-fill â€” differentiation or contribution to an existing plugin should be considered, and the Plugin Hub review process may question duplication.

## Closest plugins in detail

### 1. Recipe Checklist â€” the near-identical twin (22 installs)
- **Add flow**: `IconTextField` (Icon.SEARCH) + `JSpinner(SpinnerNumberModel(1,1,MAX,1))` quantity; ranked substring search over recipe names (exact > prefix > contains, shorter wins). Also passive in-game adds: `onMenuOptionClicked` filtered to `WidgetUtil.componentToInterface(param1)` âˆˆ {`InterfaceID.SKILL_GUIDE`, `SKILL_GUIDE_V2`, `POH_FURNITURE_CREATION`, `POH_FURNITURE_CREATION_MENU`, `SAILING_*`}, gated on `client.isKeyPressed(KeyCode.KC_SHIFT)` (deliberately does NOT add menu entries â€” Construction menus are protected by hub rules); Shipwright parts identified from the chat line "X materials: A x n, B x m." via `onChatMessage`.
- **Views**: one PluginPanel; collapsible "Materials needed" aggregate summary at top (dedup'd across goals, intermediates you chose to craft drop out, replaced by their inputs), then one collapsible section per goal showing per-material rows; collapsed header shows "ready/total" colored `ColorScheme.PROGRESS_COMPLETE_COLOR` when done.
- **Have/need display**: right-aligned `have / need` label per row, foreground color = green (`PROGRESS_COMPLETE_COLOR`) if inventory alone covers it, `Color.WHITE` if inventory+bank covers, `PROGRESS_INPROGRESS_COLOR` (yellow) if short but sub-recipe inputs on hand cover the shortfall, `PROGRESS_ERROR_COLOR` red otherwise. Item icon via `itemManager.getImage(itemId).addTo(label)` (no stack overlay at 26px row height).
- **Ownership counting**: `onItemContainerChanged`, `InventoryID.INV` and `InventoryID.BANK` (from `net.runelite.api.gameval.InventoryID` â€” the current int-constant class); every item id passed through `itemManager.canonicalize(id)` to fold notes/placeholders. Bank readable only while open, so it keeps a `BankMemory` snapshot: `volatile Map<Integer,Integer>`, saved as JSON to `new File(RuneLite.RUNELITE_DIR, "recipe-checklist")/bank-<accountHash>.json` (accountHash from `client.getAccountHash()`), flushed on LOGIN_SCREEN/HOPPING/CONNECTION_LOST and shutdown, on the injected `ScheduledExecutorService`, loaded per account on LOGGED_IN.
- **Goal model**: `Goal` tree (children = sub-recipes, scoped to parent so two goals needing oak planks stay independent); serialized as flat TSV lines `depth\tamount\tcollapsed\tname` into a single ConfigManager string key (`configManager.setConfiguration("recipe-checklist", "goals", serialized)`).
- **Recipe model**: `Recipe{name, productId, List<Ingredient>, makes}` with `Ingredient{itemId, quantity, List<Integer> same}` (interchangeable ids, e.g. watered/unwatered seedlings); `batchesFor(wanted)` handles batch outputs (1 bar â†’ 15 nails). `RecipeBook` indexes by case-insensitive name and by productId; `simplestFor(itemId)` picks the production method with fewest ingredient kinds, tie-broken toward MORE raw material (over-supply is the safe error).
- **Data**: `src/main/resources/com/recipechecklist/recipes.json`, generated offline by `tools/generate_recipes.py` joining the OSRS Wiki's **`recipe` bucket** against `infobox_item`, `infobox_construction`, `infobox_ship_part` buckets (the latter two because built furniture/ship parts are scenery with no item id). Zero network requests at runtime.
- **UX extras**: right-click â†’ "Open wiki" (`https://oldschool.runescape.wiki/w/Special:Lookup?type=item&id=<id>`, name search fallback for scenery); hover highlight rows (`DARK_GRAY_HOVER_COLOR`); no GE prices anywhere.

### 2. Recipe Tracker (193 installs) â€” same concept, plus overlay
Bundled offline wiki-generated index + **live wiki fallback with local cache** when a recipe is missing; adds a "Track materials" menu entry beside the native "Check materials" option and a configurable right-click on any item (Always / Shift+right-click / Disabled); shift-click Construction/Sailing passive capture; target quantity with multi-output rounding (cannonballs); expand/collapse per tracked recipe; recursive sub-recipe expansion where the combined shopping list replaces the intermediate with raw inputs; `inventory / required` shown in sidebar AND an in-game overlay; completed rows turn green; counts inventory, equipment, rune pouch, equipped elemental staves (not bank, notably); stable recipe keys persist between sessions.

### 3. Required Materials (77 installs) â€” Sailing/Construction scope
Tracks buildables clicked in Sailing/Construction skill guides, Boat Customisation ("Build"/"Check Materials"), Furniture Creation. Fetches recipes at runtime from the wiki (`https://oldschool.runescape.wiki/api.php?action=parse&prop=wikitext&format=json&redirects=true&page=` via injected `OkHttpClient` with User-Agent, parsing wikitext templates); resolves namesâ†’ids via `itemManager.search()` plus a full item-name index. Panel: accordion per skill (`SkillIconManager.getSkillImage`), title in `ColorScheme.BRAND_ORANGE`, per-part cards with materials, level requirements (colored met/unmet), and built-prerequisite tiers; COLOR_READY=new Color(96,220,96), white=in bank. Extra trick: a hammer button in the bank interface that filters/groups the bank by tracked build. Checks bank via snapshot on `InterfaceID.BANKMAIN` open + `InventoryID.BANK` container changes. Persists tracked requirements as gson JSON in one ConfigManager key. Config: "skipBuildable" (don't track what you can already make), "clearWhenBuilt".

### 4. Goal Tracker (`goal-tracker`, Darkforge317/rl-goal-tracker, orig. Toofifty; 3,962 installs)
General goal/task plugin, not recipe-aware. Task types: SkillLevel/SkillXp/Quest/Item/Manual. `ItemTask{itemId, itemName, quantity, acquired}` auto-updates acquired = min(total held, quantity), status NOT_STARTED/IN_PROGRESS/COMPLETED; renders "acquired/quantity x Name". `ItemCache` persists **whole inventories** (`Map<Integer /*inventoryId*/, Item[]>` as gson in a config key) plus an itemIdâ†”notedId map so counts survive logout; totals recomputed from all cached containers (inventory+bank+equipment). UX: goal cards with progress bars, pinning, presets (Barrows/Void/Ironman), quest prerequisite auto-add, JSON import/export, right-click card menu, undo stack.

### 5. Banked Experience (247,666 installs â€” the genre king)
Inverse direction: reads what you HAVE banked and computes potential XP. Data is **hand-maintained Java enums** (`ExperienceItem`, `Activity`, `Secondaries`) rather than generated JSON. Tracks `InventoryID.BANK`, `InventoryID.SEED_VAULT`, `InventoryID.INV`, looting bag (container id 516), fossil chest via widget; per-container `Map<Integer,Integer>` maps merged on demand; **no persistence** of bank between sessions (documented limitation users complain about â€” recipe-checklist/bank-memory style persistence is the fix). UI: skill dropdown with icons (`ComboBoxIconEntry`/`ComboBoxIconListRenderer`), `SelectionGrid` of item icons, per-item activity dropdown when multiple methods exist, `ExpandableSection` for secondaries, "include output items" toggle (chains logsâ†’unstrungâ†’strung). Deliberately refreshes only on skill reselect for performance.

### 6. Bank Memory (61,972 installs)
Remembers bank contents per account (all accounts), searchable list view with item icons + quantities + GE/HA value (`ValueDisplayPanel`), named snapshots, **diff view between two saves** (`ItemListDiffGenerator`), clipboard export. Persistence via ConfigManager (`ConfigReaderWriter`, `PluginDataStore` with a data lock + listener pattern). Precedent that per-account bank snapshots in config/files are accepted hub practice.

### Supporting genre data points
- **Inventory Setups (229,319)**: the UX gold standard for "target set vs what you have" â€” bank filtering to the setup's items, red highlighting of mismatches (stack/fuzzy/unordered modes), sections, favorites, compact/icon modes, export/import codes, per-setup config stored as compressed JSON in config profile.
- **Goal Planner (`goalplanner`, AKAddons, 577)**: item grinds count inventory+bank, right-click in-game add, colored sections, share codes, auto-tracking.
- **Who Can Craft (33)**: GIM-oriented; search craftable, shows skill reqs + material counts across inventory/bank/group storage, green=sufficient red=insufficient.
- **Herblore Recipes (39,576)**: tooltip-on-hover recipe hints; shows demand for recipe data surfaced in-game.
- **Supplies Tracker (46,654)**: consumption tracking with GE cost â€” different genre (retrospective, not planning).
- **Profit Calculator (4,380)**: side-panel input/output lists priced from GE averages (`itemManager` search + prices), right-click to override price.

## Common practice (table stakes) for a materials-needed plugin
1. Side panel via `PluginPanel` + `NavigationButton.builder().tooltip(...).icon(ImageUtil.loadImageResource(getClass(), "icon.png")).priority(n).panel(panel).build()` + `clientToolbar.addNavigation`.
2. Search-field add (`net.runelite.client.ui.components.IconTextField`, Icon.SEARCH) with quantity spinner; optional passive in-game add gated on shift (never inject menu entries into Construction â€” hub rule).
3. Count inventory AND bank; bank via snapshot kept across sessions (config JSON or `RuneLite.RUNELITE_DIR` file keyed by `client.getAccountHash()`), updated on `ItemContainerChanged` for `InventoryID.BANK`; always `itemManager.canonicalize()` ids.
4. `have / need` text with RuneLite `ColorScheme` colors: PROGRESS_COMPLETE_COLOR green / PROGRESS_ERROR_COLOR red minimum; white "in bank" and yellow "craftable" are refinements. Item icons via `itemManager.getImage(...)` (AsyncBufferedImage.addTo(JLabel)).
5. Collapsible per-product sections (+/- arrow JLabel, `DARKER_GRAY_COLOR` row bg, hover `DARK_GRAY_HOVER_COLOR`), aggregate shopping-list summary, per-section ready-count in collapsed header; progress bars only in the goal-tracker family.
6. Persistence through `ConfigManager.setConfiguration(group, key, serializedString)` (survives via RuneLite profiles/cloud) â€” files under RUNELITE_DIR only for bulky per-account data.
7. Recipe data: generated offline from OSRS Wiki structured buckets (`recipe`, `infobox_item`, `infobox_construction`, `infobox_ship_part`) into a bundled resources JSON; runtime wiki HTTP only as fallback (must use the injected OkHttpClient + custom User-Agent). Model batch outputs (`makes`), multiple production methods per product, interchangeable item ids.
8. Right-click â†’ open wiki (`Special:Lookup?type=item&id=`); JSON export/import is common in goal-family plugins.
9. GE prices are NOT table stakes in this genre (recipe-checklist/recipe-tracker/goal-tracker have none) but cheap to add via `itemManager.getItemPrice(int)` (wiki-price aware); would be a differentiator ("cost to finish the list").
10. Build scaffolding: standard example-plugin gradle (`compileOnly net.runelite:client:latest.release` from `https://repo.runelite.net`, lombok 1.18.30, `options.release.set(11)`, test-scope run task with `--developer-mode`), `runelite-plugin.properties` with displayName/author/description/tags/plugins/build=standard.

## Key facts
- DUPLICATE EXISTS: 'Recipe Checklist' (hub slug recipe-checklist, repo KludensVogter/Recipes_Checklist, v1.1.0, 22 active installs) is feature-for-feature the planned Material Checklist plugin: search+quantity add, have/need per material counting inventory+persistent bank snapshot, aggregated materials-needed summary, recursive sub-recipe drill-down, green/white/yellow/red color coding, ~4,600 wiki-generated recipes.json
- Second near-duplicate: 'Recipe Tracker' (nassarao/recipe-tracker, 193 installs) â€” bundled wiki recipe index + live wiki fallback, recursive sub-recipes with shopping list substituting intermediates for raw inputs, sidebar + in-game overlay, counts inventory/equipment/rune-pouch (not bank)
- Third overlap: 'Required Materials' (SchauweM/runelite-required-materials, 77 installs) â€” Sailing + Construction only, runtime wiki API recipe fetch, bank-grouped filter button inside the bank interface, accordion-per-skill panel
- Install counts (api.runelite.net/pluginhub/shields, 2026-09-05): banked-experience 247,666; inventory-setups 229,319; bank-memory 61,972; supplies-tracker 46,654; herblorerecipes 39,576; profit-calculator 4,380; goal-tracker 3,962; goalplanner 577; skilling-cost-calculator 245; recipe-tracker 193; required-materials 77; who-can-craft 33; recipe-checklist 22
- Current API (verified on GitHub master): inventory container int constants live in net.runelite.api.gameval.InventoryID (InventoryID.INV, InventoryID.BANK, InventoryID.SEED_VAULT); interface ids in net.runelite.api.gameval.InterfaceID (e.g. InterfaceID.BANKMAIN, InterfaceID.SKILL_GUIDE, InterfaceID.SKILL_GUIDE_V2, InterfaceID.POH_FURNITURE_CREATION, InterfaceID.SAILING_CUSTOMISATION); WidgetUtil.componentToInterface(int) maps a MenuOptionClicked param1 to its interface id
- ItemManager (net.runelite.client.game.ItemManager, verified master): public int getItemPrice(int itemID); public int getItemPriceWithSource(int itemID, boolean useWikiPrice); public int getWikiPrice(ItemPrice); public int canonicalize(int itemID) (folds noted items and bank placeholders to canonical id); public ItemComposition getItemComposition(int); public AsyncBufferedImage getImage(int itemId[, int quantity, boolean stackable]); public List<ItemPrice> search(String) (used by required-materials for name->id)
- Bank is only readable while open: genre-standard pattern is snapshotting on ItemContainerChanged for InventoryID.BANK into an in-memory Map<Integer,Integer> and persisting it â€” recipe-checklist writes JSON to new File(RuneLite.RUNELITE_DIR, "recipe-checklist")/bank-<accountHash>.json keyed by client.getAccountHash(), flushing on LOGIN_SCREEN/HOPPING/CONNECTION_LOST and shutDown via the injected ScheduledExecutorService; goal-tracker instead persists gson'd Map<inventoryId, Item[]> plus an itemId<->notedId map in config keys; bank-memory persists via ConfigManager
- Checklist state persistence convention: small state goes through ConfigManager.setConfiguration(group, key, serializedString) â€” recipe-checklist serializes its goal tree as TSV lines 'depth\tamount\tcollapsed\tname' under group 'recipe-checklist' key 'goals'; required-materials and goal-tracker store gson JSON strings in config keys
- Recipe data sourcing convention: generate offline from the OSRS Wiki's structured buckets â€” join the 'recipe' bucket (materials+quantities) with 'infobox_item', 'infobox_construction', 'infobox_ship_part' (furniture/ship parts are scenery with no item id) via a python tools/generate_recipes.py, bundle as resources JSON; model fields {name, productId, ingredients[{itemId, quantity, same[]}], makes} with batch rounding batchesFor(wanted)=(wanted+makes-1)/makes
- Multiple production methods per product are real (iron bar furnace vs superheat, blast furnace halves coal): recipe-checklist's RecipeBook.simplestFor(itemId) picks fewest ingredient kinds, ties broken toward MORE raw material because over-supplying is the harmless direction for a shopping list
- Color-coding convention (net.runelite.client.ui.ColorScheme): PROGRESS_COMPLETE_COLOR green = enough in inventory, Color.WHITE = enough counting bank, PROGRESS_INPROGRESS_COLOR yellow = short but craftable from stock on hand, PROGRESS_ERROR_COLOR red = short; row background DARKER_GRAY_COLOR, hover DARK_GRAY_HOVER_COLOR, muted text LIGHT_GRAY_COLOR, headers BRAND_ORANGE; required-materials uses custom green new Color(96,220,96)
- In-game add convention: passively observe MenuOptionClicked / ChatMessage â€” NEVER add menu entries to the Construction build menu (recipe-checklist javadoc: 'adding an entry to the right-click menu is exactly what the plugin rules forbid for Construction'); shift-gating via client.isKeyPressed(KeyCode.KC_SHIFT) prevents accidental adds; Shipwright parts are identified from the game chat line 'X materials: Plank x 4, ...' (Pattern "^(.+?) materials: (.+?)\\.?$")
- Recursive drill-down precedent: recipe-checklist models goals as a tree â€” clicking a craftable material adds its recipe as a CHILD of that goal scoped to the parent (two goals needing oak planks keep independent entries; removing/rescaling a parent cascades); the top summary lists only leaf materials still to acquire, dropping intermediates the user chose to craft; recipes listing their own product as input are dropped at generation time to prevent infinite drill-down
- Goal Tracker item tasks: ItemTask{itemId, itemName, quantity, acquired} with Status NOT_STARTED/IN_PROGRESS/COMPLETED, acquired=min(totalHeld, quantity), display '%d/%d x %s'; uses progress bars on goal cards, presets, pinning, quest prerequisite auto-seeding, JSON import/export â€” the UX conventions for the 'goal card' style
- Banked Experience (the 247k-install genre leader) hardcodes its data as Java enums (ExperienceItem/Activity/Secondaries), does NOT persist bank between sessions (documented pain point), tracks bank/seed vault/inventory/looting bag (container id 516), refreshes UI only on explicit skill reselect for performance, and uses per-item activity dropdowns when an item has multiple training methods
- Right-click 'Open wiki' convention: https://oldschool.runescape.wiki/w/Special:Lookup?type=item&id=<itemId>, falling back to Special:Search?search=<name> for scenery with no item id; runtime wiki API calls (required-materials, recipe-tracker fallback) use the injected OkHttpClient with an explicit User-Agent against https://oldschool.runescape.wiki/api.php?action=parse&prop=wikitext&format=json&redirects=true&page=
- Gradle/packaging conventions (from recipe-checklist, matching example-plugin): compileOnly 'net.runelite:client:latest.release' from maven repo https://repo.runelite.net (includeGroupByRegex net\.runelite.*), lombok 1.18.30 compileOnly+annotationProcessor, junit 4.12 + net.runelite:client + net.runelite:jshell testImplementation, options.release.set(11), JavaExec 'run' task on the test classpath launching <Plugin>Test with --developer-mode --debug; runelite-plugin.properties fields: displayName/author/description/tags/version/plugins/build=standard; hub manifest is a 2-line file plugins/<slug> with repository= and commit=
- Threading conventions observed: item names/compositions and container reads happen on the client thread (clientThread.invoke), results handed to Swing via SwingUtilities.invokeLater; blocking file IO on the injected ScheduledExecutorService; BankMemory map is volatile-replaced, never mutated, for cross-thread reads
- GE price integration is NOT table stakes in this genre â€” recipe-checklist, recipe-tracker, required-materials and goal-tracker have none; bank-memory/profit-calculator show values via itemManager GE prices; adding 'gp cost to buy missing materials' would differentiate Material Checklist from the incumbents

## Code patterns
// ===== recipe-checklist (KludensVogter/Recipes_Checklist) â€” the near-identical incumbent =====
// src/main/java/com/recipechecklist/RecipeChecklistPlugin.java
@PluginDescriptor(name = "Recipe Checklist",
  description = "Build a checklist of items you want to make and track the materials you still need",
  tags = {"checklist", "materials", "crafting", "smithing", "skilling", "shopping"})
public class RecipeChecklistPlugin extends Plugin

// Container tracking (current gameval API):
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
@Subscribe public void onItemContainerChanged(ItemContainerChanged event) {
  final int id = event.getContainerId();
  if (id == InventoryID.BANK) { rememberBank(event.getItemContainer()); } // only moment bank is readable
  if (id == InventoryID.INV || id == InventoryID.BANK) { refresh(); }
}
// Canonicalize when tallying (folds noted items + placeholders):
totals.merge(itemManager.canonicalize(item.getId()), item.getQuantity(), Integer::sum);
// Client-thread read, EDT hand-off:
clientThread.invoke(() -> { ...counts...; SwingUtilities.invokeLater(() -> panel.applyCounts(counts)); });
// Passive in-game add (no menu injection; shift-gated):
final int component = event.getParam1();
if (!WATCHED_INTERFACES.contains(WidgetUtil.componentToInterface(component))) return; // SKILL_GUIDE, SKILL_GUIDE_V2, POH_FURNITURE_CREATION(_MENU), SAILING_*
return !config.requireShiftToAdd() || client.isKeyPressed(KeyCode.KC_SHIFT);
// Bank snapshot persistence (BankMemory.java): file per account
this.directory = new File(RuneLite.RUNELITE_DIR, "recipe-checklist");
private File fileFor(long accountHash) { return accountHash == -1 ? null : new File(directory, "bank-" + accountHash + ".json"); }
// loaded via client.getAccountHash() on LOGGED_IN, flushed on LOGIN_SCREEN/HOPPING/CONNECTION_LOST + shutDown, on injected ScheduledExecutorService

// Recipe model (Recipe.java):
public class Recipe { public String name; public int productId; public List<Ingredient> ingredients; public int makes;
  int batchesFor(int wanted) { final int batch = Math.max(1, makes); return (wanted + batch - 1) / batch; }
  public static class Ingredient { public int itemId; public int quantity; public List<Integer> same; } }
// recipes.json entry shape: {"name":"Steel bar","productId":2353,"ingredients":[{"itemId":440,"quantity":1},{"itemId":453,"quantity":2}]}

// Default production method choice (RecipeBook.simplestFor): fewest ingredient kinds; ties -> MORE raw material (over-supply is harmless)

// Color rules (RecipeChecklistPanel.MaterialRow.colorFor):
if (inInventory >= needed) return ColorScheme.PROGRESS_COMPLETE_COLOR;   // green
if (have >= needed)        return Color.WHITE;                            // in bank
if (canMakeShortfall(counts)) return ColorScheme.PROGRESS_INPROGRESS_COLOR; // yellow: sub-recipe inputs on hand
return ColorScheme.PROGRESS_ERROR_COLOR;                                  // red
count.setText(have + " / " + needed);
// Wiki right-click: "https://oldschool.runescape.wiki/w/Special:Lookup?type=item&id=" + itemId
// Goal tree serialization: lines of depth\tamount\tcollapsed\tname -> configManager.setConfiguration("recipe-checklist", "goals", s)

// ===== goal-tracker (Darkforge317/rl-goal-tracker) =====
// models/task/ItemTask.java: fields itemId/itemName/quantity/acquired; recomputeFromCount(int count) clamps + sets Status COMPLETED/IN_PROGRESS/NOT_STARTED
// services/TaskUpdateService.java: task.setAcquired(Math.min(itemCache.getTotalQuantity(task.getItemId()), task.getQuantity()));
// ItemCache.java: persists Map<Integer /*inventoryId*/, Item[]> + Map<Integer,Integer> itemNoteMap as gson strings in config keys; getTotalQuantity sums item + noted counterpart

// ===== banked-experience (TheStonedTurtle) container set =====
if (ev.getContainerId() == InventoryID.BANK
  || (ev.getContainerId() == InventoryID.SEED_VAULT && config.grabFromSeedVault())
  || (ev.getContainerId() == InventoryID.INV && config.grabFromInventory())
  || (ev.getContainerId() == LOOTING_BAG_ID /* 516 */ && config.grabFromLootingBag())) { updateItemsFromItemContainer(...); }

// ===== required-materials (SchauweM) live wiki fetch =====
private static final String API_URL = "https://oldschool.runescape.wiki/api.php?action=parse&prop=wikitext&format=json&redirects=true&page=";
// injected OkHttpClient, Request.Builder().url(url).header("User-Agent", USER_AGENT).build(), async Callback, hop back via ClientThread
// Panel colors: COLOR_READY = new Color(96, 220, 96); COLOR_IN_BANK = Color.WHITE; headers ColorScheme.BRAND_ORANGE; skill icons via SkillIconManager.getSkillImage(skill, true)

// ===== ItemManager current signatures (runelite master, net/runelite/client/game/ItemManager.java) =====
public int getItemPrice(int itemID)
public int getItemPriceWithSource(int itemID, boolean useWikiPrice)
public int getWikiPrice(ItemPrice itemPrice)
public int canonicalize(int itemID)
public ItemComposition getItemComposition(int itemId)
public AsyncBufferedImage getImage(int itemId)
public AsyncBufferedImage getImage(int itemId, int quantity, boolean stackable)
public List<ItemPrice> search(String)  // name -> ItemPrice results (used for name->id resolution)

// ===== build.gradle (recipe-checklist; matches example-plugin conventions) =====
repositories { mavenLocal(); maven { url = 'https://repo.runelite.net'; content { includeGroupByRegex("net\\.runelite.*") } }; mavenCentral() }
def runeLiteVersion = 'latest.release'
dependencies { compileOnly group: 'net.runelite', name:'client', version: runeLiteVersion
  compileOnly 'org.projectlombok:lombok:1.18.30'; annotationProcessor 'org.projectlombok:lombok:1.18.30'
  testImplementation 'junit:junit:4.12'
  testImplementation group: 'net.runelite', name:'client', version: runeLiteVersion
  testImplementation group: 'net.runelite', name:'jshell', version: runeLiteVersion }
tasks.withType(JavaCompile).configureEach { options.encoding = 'UTF-8'; options.release.set(11) }
tasks.register('run', JavaExec) { classpath = sourceSets.test.runtimeClasspath; mainClass = 'com.recipechecklist.RecipeChecklistPluginTest'; args "--developer-mode", "--debug" }
// runelite-plugin.properties: displayName= author= description= tags= version= plugins=com.recipechecklist.RecipeChecklistPlugin build=standard
// hub manifest plugins/recipe-checklist: repository=https://github.com/KludensVogter/Recipes_Checklist.git \n commit=<sha>

## Risks
- Duplication risk: 'Recipe Checklist' (recipe-checklist) already implements essentially the entire Material Checklist spec, and 'Recipe Tracker' (recipe-tracker) implements most of it â€” the Plugin Hub review may push back on a third near-identical plugin, and users searching 'materials'/'checklist' will find the incumbents; consider differentiating (GE cost-to-complete, better recipe coverage, Herblore/Cooking-first UX, overlay) or contributing upstream
- Sailing exists in OSRS as of these sources (InterfaceID.SAILING_* constants, Shipwright/Boat Customisation interfaces) â€” any pre-2025 mental model of the skill list is stale; recipe data must cover it or users will notice
- The old net.runelite.api.InventoryID enum / ItemID location memory is stale: current plugins import int constants from net.runelite.api.gameval.InventoryID and net.runelite.api.gameval.InterfaceID (e.g. InventoryID.INV not INVENTORY, InterfaceID.BANKMAIN); verify every constant against master before use
- Bank contents are only readable while the bank is open â€” naive client.getItemContainer(BANK) reads collapse to zero/stale between visits; a persistent snapshot layer is mandatory for 'owned' counts, and Banked Experience's lack of it is its top user complaint
- Noted items, bank placeholders, and item variants (watered vs unwatered seedlings, potion doses) silently break counts unless every id passes through itemManager.canonicalize() and recipes model interchangeable ids ('same' lists)
- Hub rules forbid adding menu entries to protected interfaces (Construction build menu explicitly, per recipe-checklist's javadoc) â€” in-game add must be passive observation of MenuOptionClicked/ChatMessage, ideally shift-gated; runtime wiki HTTP must use the injected OkHttpClient with a proper User-Agent and never block the client thread
- Recipe data quality is the real moat and the real maintenance burden: wiki 'recipe' bucket has ~118 unresolvable entries, duplicate names, recipes listing their own product (infinite recursion), multi-method products, and batch outputs â€” recipe-checklist's generate_recipes.py documents each pitfall and its resolution; hand-maintained enum data (Banked Experience) is acknowledged in its own README as perpetually incomplete
- Install counts show the checklist-style incumbents have tiny adoption (22/77/193) vs 247k for Banked Experience â€” either the niche demand is genuinely small, or the incumbents are undiscovered/new; validate demand before heavy investment
- GitHub trees API used unauthenticated (60 req/hr limit) â€” findings are from HEAD of each repo on 2026-09-05; pinned hub commits (in plugins/<slug> manifests) may lag repo HEAD slightly

## Sources
- https://github.com/runelite/plugin-hub (plugins/ manifests, full tree via api.github.com/repos/runelite/plugin-hub/git/trees/master?recursive=1)
- https://raw.githubusercontent.com/runelite/plugin-hub/master/plugins/recipe-checklist (and manifests for required-materials, recipe-tracker, goal-tracker, goalplanner, banked-experience, who-can-craft, herblorerecipes, profit-calculator, skilling-cost-calculator, supplies-tracker, bank-memory, inventory-setups, gauntlet-crafting)
- https://github.com/KludensVogter/Recipes_Checklist (README.md, RecipeChecklistPlugin.java, Goal.java, Recipe.java, RecipeBook.java, BankMemory.java, ItemCount.java, RecipeChecklistConfig.java, RecipeChecklistPanel.java, build.gradle, runelite-plugin.properties)
- https://github.com/SchauweM/runelite-required-materials (README.md, RequiredMaterialsPlugin.java, MaterialsManager.java, TrackedRequirement.java, RequiredMaterial.java, WikiRecipeService.java, RequiredMaterialsPanel.java, RequiredMaterialsConfig.java)
- https://github.com/nassarao/recipe-tracker (README.md)
- https://github.com/Darkforge317/rl-goal-tracker (README.md, models/task/ItemTask.java, services/TaskUpdateService.java, ItemCache.java, GoalManager.java, full java file tree)
- https://github.com/TheStonedTurtle/banked-experience (README.md, BankedExperiencePlugin.java, full java file tree)
- https://github.com/AKAddons/runelite-goal-planner (README.md)
- https://github.com/visagex/Who-Can-Craft-Group-Ironman-Crafting-Helper (README.md)
- https://github.com/climbridecode/herblore-recipes (README.md)
- https://github.com/LlemonDuck/profit-calculator (README.md)
- https://github.com/Lazyfaith/runelite-bank-memory-plugin (README.md, file tree, data/PluginDataStore.java)
- https://github.com/dillydill123/inventory-setups (README.md)
- https://github.com/pwatts6060/SuppliesTrackerExternal (README.md)
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-client/src/main/java/net/runelite/client/game/ItemManager.java
- https://api.runelite.net/pluginhub/shields/installs/plugin/<slug> (install counts for all 13 candidates, fetched 2026-09-05)
