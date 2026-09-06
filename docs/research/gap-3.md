# Gap follow-up: What backs the add-a-product search box, given that itemManager.search(String) only indexes TRADEABLE items? The panel-ui agent recommends the GrandExchangeSearchPanel pattern (executor -> itemManager.search -> clientThread -> Swing), but that index is the GE price list (~tradeables only, per the itemids-data agent's verified prices.js payload), so untradeable finished goods â€” most Construction outputs, ship parts, various untradeable craftables â€” would be unfindable; the incumbent recipe-checklist instead searches its own bundled recipe-name index with ranked substring matching. Related unresolved detail: where do product/material display names for the panel come from â€” the wiki-generated JSON carries names, but the hand-written-Java route has only cryptic gameval constant names (BRONZECRAFTWIRE, BRUT_BRONZE_SPEAR) plus ItemComposition.getName()/getMembersName(), which is client-thread-only, implying either a startup client-thread name-resolution pass cached for the EDT or names bundled in the data. Concrete research/decision: confirm itemManager.search's tradeable-only coverage against master, then specify the search index (recipe-book names vs itemManager) and the name-resolution/caching flow between client thread and EDT.

## Verdict: itemManager.search(String) is tradeable-only by design and by measured payload â€” the add-a-product search box MUST be backed by a bundled recipe-name index (the incumbent recipe-checklist pattern), not the GE search pattern. Name display is a hybrid: product/recipe names come bundled from the wiki-generated JSON (EDT-safe, no client thread needed), while material/owned-item names are resolved on the client thread during count refreshes and handed to the EDT inside a plain value object.

### 1. itemManager.search coverage â€” CONFIRMED tradeable-only, three independent ways
- **Javadoc on master** (`runelite-client/src/main/java/net/runelite/client/game/ItemManager.java`, package `net.runelite.client.game`): the method's javadoc literally reads `Search for tradeable items based on item name`. Signature: `public List<ItemPrice> search(String itemName)` â€” a linear case-insensitive `contains` scan over `itemPrices.values()`, where `private Map<Integer, ItemPrice> itemPrices` is populated solely from `ItemClient.getPrices()` (`GET {apiBase}/item/prices.js`, refreshed every 30 min via `scheduledExecutorService.scheduleWithFixedDelay(this::refreshPrices, 0, 30, TimeUnit.MINUTES)` and on login). There is NO other name/search structure in ItemManager (only `itemStats` and image/outline caches).
- **Live payload measured 2026-09-05**: fetched `https://api.runelite.net/runelite-1.12.38/item/prices.js` (version from static.runelite.net/bootstrap.json). It contains **4,681 items**. Untradeables absent: Crystal saw, Salve amulet (id 4081), Dragon defender, Barrows gloves, Infernal cape, Rada's blessing â€” all 0 hits. Tradeable materials present: Molten glass, Bronze wire, Bow string, Mahogany plank, Teak plank.
- **The GE panel itself filters further**: `GrandExchangeSearchPanel.processResult` skips any result where `!itemComp.isGeTradeable()` â€” the core client treats the price list as GE-only.

### 2. Quantified gap for THIS plugin's product space (measured against the incumbent's wiki-generated recipes.json, 4,653 recipes)
- 3,504 distinct non-zero productIds; **1,723 of them (49.2%) are NOT in prices.js** â†’ invisible to itemManager.search. Example: Salve amulet (4081) is a craftable recipe product and absent from the price index.
- **87 recipes have `productId: 0`** â€” no item ID exists at all (mounted trophy heads, ship parts like "Camphor hull (Raft)", Mastering Mixology conveyor products). These can NEVER be surfaced by any item-based search; only a recipe-name index can represent them. Construction flatpacks (e.g. "4-poster" â†’ 8036) do have item IDs, but non-flatpack buildables do not.
- Conclusion: building the add-product box on itemManager.search structurally forfeits ~half the advertised product space; the GrandExchangeSearchPanel pattern's *threading shape* is still the right reference, but its *index* is wrong for this plugin.

### 3. The incumbent's search design (KludensVogter/Recipes_Checklist @ b221cad7355068cec90b7e4518b77a431f14ed36, pinned by runelite/plugin-hub/plugins/recipe-checklist)
- `RecipeBook` (class `com.recipechecklist.RecipeBook`): loads bundled `recipes.json` (~858 KB, wiki Bucket-API-generated) into `TreeMap byName` (case-insensitive keys) + `HashMap<Integer, List<Recipe>> byProductId`. `List<String> search(String query, int limit)`: substring filter over `byName.keySet()`, then sort by `rank` (0 = exact, 1 = startsWith, 2 = contains), ties broken by shorter name then alpha. O(n) over ~4.6k names â€” runs synchronously on the EDT from a DocumentListener (`insertUpdate â†’ showResults()`), no executor, no clientThread. This works precisely because the whole index is plugin-local strings.
- `Recipe` fields: `String name; int productId; List<Ingredient> ingredients; int makes` â€” Ingredient: `int itemId; int quantity; List<Integer> same` (interchangeable alternatives). **Ingredients carry IDs only, no names.**

### 4. Name resolution / threading â€” the settled design
- **Product names**: bundled in recipes.json as wiki names (generator maps wiki `infobox_item` nameâ†’id; also queries `infobox_construction` and `infobox_ship_part` buckets and the `recipe` bucket at `https://oldschool.runescape.wiki/api.php`). Search + goal list render entirely on the EDT with zero client-thread traffic â€” search works even before login.
- **Material names**: resolved lazily on the client thread each refresh. Verified verbatim flow in `RecipeChecklistPlugin.refresh()`: `clientThread.invoke(() -> { tally inventory; for each wanted id: counts.put(itemId, new ItemCount(inv, bank, itemManager.getItemComposition(itemId).getName())); SwingUtilities.invokeLater(() -> panel.applyCounts(counts)); })`. `ItemCount` is an immutable `{int inventory; int bank; String name}` handed across the thread boundary. The panel never calls getItemComposition.
- **Thread contract for getItemComposition**: current master body is just `return client.getItemDefinition(itemId);` with `@Nonnull`, no assert, no cache â€” commit e98c3da7 (2021-10-01, "rl-client: use vanilla ItemComposition cache") REMOVED the old `LoadingCache` + `assert client.isClientThread() : "getItemComposition must be called on client thread"` + PostItemComposition/GameStateChanged invalidation. The api javadoc for `Client.getItemDefinition(int)` documents no thread requirement, BUT core code still universally hops to the client thread before calling it (GE panel: `clientThread.invokeLater(() -> processResult(...))` does compositions/stats/`itemManager.getImage` there, then `SwingUtilities.invokeLater` for UI; `ItemManager.getImage` internally does `clientThread.invoke` and its javadoc warns it returns a blank image off the game thread). Treat composition lookup as client-thread-only; the injected client's cache-archive loading is not documented thread-safe.
- **ClientThread semantics** (`net.runelite.client.callback.ClientThread`): `invoke(Runnable/BooleanSupplier)` runs immediately if already on the client thread, else queues; `invokeLater` always queues for the next client frame; suppliers returning false are re-run. Package `net.runelite.client.callback`.
- **Icons for untradeables**: `itemManager.getImage(int itemId)` works for ANY item id (renders from game cache via clientThread; `AsyncBufferedImage.addTo(JLabel)` fills in asynchronously, class `net.runelite.client.util.AsyncBufferedImage`), so untradeable products with real item IDs still get icons; productId=0 recipes use `Recipe.iconItemId()` = first ingredient's id as fallback.
- **Gameval note**: `net.runelite.api.gameval.ItemID` (auto-generated, 2.4 MB) DOES carry the display name â€” but only in javadoc (`/** Bronze wire */ public static final int BRONZECRAFTWIRE = 1794;`, `/** Bronze hasta */ public static final int BRUT_BRONZE_SPEAR = 11367;`), unavailable at runtime. So the hand-written-Java route has no runtime names without a client-thread resolution pass or bundled name data â€” another point for bundling wiki names in the recipe JSON.

### 5. Recommended architecture (settling the conflict)
Back the add-product search with a bundled recipe-name index (RecipeBook pattern: byName TreeMap + ranked substring search, EDT-synchronous, cap results ~the incumbent's MAX_RESULTS / GE's MAX_SEARCH_ITEMS=100). Keep the GE panel's thread-hop shape only where game data is touched: any composition/name/count/icon resolution goes `clientThread.invoke(...)` â†’ build immutable snapshot (ItemCount-style) â†’ `SwingUtilities.invokeLater(panel::apply...)`. Bundle product display names in the JSON (wiki-sourced); resolve material names on the client thread during refresh and cache them in the snapshot map (optionally memoize idâ†’name in a plugin-side ConcurrentHashMap so each id is resolved once). Optionally use `getMembersName()` instead of `getName()` to avoid the " (Members)" suffix on F2P worlds.

## Key facts
- ItemManager.search(String) javadoc on runelite master literally says 'Search for tradeable items based on item name'; it linearly scans itemPrices.values() with lowercase contains() and returns List<ItemPrice> (net.runelite.http.api.item.ItemPrice: id, name, price + wiki price fields).
- itemPrices is loaded exclusively from ItemClient.getPrices() = GET {apiBase}/item/prices.js; refreshed every 30 minutes via scheduledExecutorService and re-fetched on GameStateChanged >= LOGGING_IN if older than 30 min. There is no other searchable name structure anywhere in ItemManager.
- Live prices.js for RuneLite 1.12.38 (fetched 2026-09-05 from api.runelite.net) contains 4,681 items; Crystal saw, Salve amulet, Dragon defender, Barrows gloves, Infernal cape and Rada's blessing are absent; Molten glass, Bronze wire, Bow string, Mahogany plank, Teak plank are present.
- Measured against the incumbent recipe-checklist's wiki-generated recipes.json (4,653 recipes): 1,723 of 3,504 distinct non-zero product item IDs (49.2%) are NOT in prices.js, and 87 recipes have productId=0 (no item ID exists: mounted heads, ship hulls like 'Camphor hull (Raft)', Mastering Mixology conveyor outputs) â€” unreachable by ANY item-based search.
- GrandExchangeSearchPanel's threading pattern (verified on master): searchBar action -> executor.execute(() -> priceLookup(false)) -> itemManager.search (thread-safe, immutable map) -> clientThread.invokeLater(() -> processResult(...)) where getItemComposition/getItemStats/getImage run, then SwingUtilities.invokeLater for UI; it additionally filters !itemComp.isGeTradeable() and caps at MAX_SEARCH_ITEMS=100.
- ItemManager.getItemComposition(int) on master is just 'return client.getItemDefinition(itemId);' (@Nonnull, no cache). Commit e98c3da7 (2021-10-01, 'rl-client: use vanilla ItemComposition cache') removed the LoadingCache AND the line 'assert client.isClientThread() : "getItemComposition must be called on client thread"'. Client.getItemDefinition javadoc documents no thread rule, but all core call sites still hop to the client thread first â€” treat it as client-thread-only.
- Incumbent plugin identified: plugin-hub file plugins/recipe-checklist pins repository=https://github.com/KludensVogter/Recipes_Checklist.git commit=b221cad7355068cec90b7e4518b77a431f14ed36. Its search is RecipeBook.search(String query, int limit): substring filter over a case-insensitive TreeMap keySet, ranked exact(0) < startsWith(1) < contains(2), ties by shorter name then compareToIgnoreCase â€” run synchronously on the EDT from a DocumentListener; no executor, no clientThread involved in search.
- Incumbent name-resolution flow (RecipeChecklistPlugin.refresh(), verbatim verified): clientThread.invoke(() -> { tally inventory via client.getItemContainer(InventoryID.INV) + itemManager.canonicalize; for each tracked id build new ItemCount(inv, bank, itemManager.getItemComposition(itemId).getName()); SwingUtilities.invokeLater(() -> panel.applyCounts(counts)); }) â€” names cross to the EDT inside an immutable ItemCount {int inventory; int bank; String name}.
- Product display names are bundled: recipes.json entries are {name, productId, ingredients:[{itemId, quantity, same:[...]}], makes} â€” recipe/product names are wiki names written by tools/generate_recipes.py (OSRS wiki Bucket API at oldschool.runescape.wiki/api.php, buckets infobox_item, infobox_construction, infobox_ship_part, recipe); ingredient entries carry IDs only, so material names must be resolved at runtime on the client thread.
- net.runelite.api.gameval.ItemID (auto-generated, ~2.4 MB) carries display names only as javadoc: '/** Bronze wire */ public static final int BRONZECRAFTWIRE = 1794;' and '/** Bronze hasta */ public static final int BRUT_BRONZE_SPEAR = 11367;' â€” names are not available at runtime from the constants.
- ClientThread (net.runelite.client.callback.ClientThread): invoke(BooleanSupplier) runs the task immediately when client.isClientThread(), otherwise delegates to invokeLater which queues for the next client frame; suppliers returning false are re-queued. invoke() is therefore the right wrapper for 'run on client thread ASAP'.
- itemManager.getImage(int) works for any item id including untradeables (renders from the game cache): returns net.runelite.client.util.AsyncBufferedImage which fills in via clientThread.invoke and offers addTo(JLabel) for EDT-safe async icon injection; ItemManager's javadoc warns the image is blank if requested off the game thread until loaded.
- ItemComposition.getName() appends ' (Members)' on free worlds; ItemComposition.getMembersName() returns the real name on any world â€” prefer getMembersName() for display names resolved at runtime.
- On current master the http-api classes (ItemPrice etc.) come from external artifact net.runelite.arn:http-api:1.2.23 (libs.versions.toml alias rl-http-api); runelite master now builds with Gradle (build.gradle.kts) and modules cache, runelite-api, runelite-client, runelite-gradle-plugin, runelite-jshell â€” hub plugins get ItemPrice transitively via net.runelite:client.
- The incumbent uses current gameval InventoryID (net.runelite.api.gameval.InventoryID.INV / .BANK) in onItemContainerChanged, confirming the modern container-ID API in a hub-accepted plugin.

## Code patterns
// ===== 1. ItemManager.search on master â€” tradeable-only (runelite-client/src/main/java/net/runelite/client/game/ItemManager.java)
/**
 * Search for tradeable items based on item name
 */
public List<ItemPrice> search(String itemName)
{
	itemName = itemName.toLowerCase();
	List<ItemPrice> result = new ArrayList<>();
	for (ItemPrice itemPrice : itemPrices.values())
	{
		final String name = itemPrice.getName();
		if (name.toLowerCase().contains(itemName))
		{
			result.add(itemPrice);
		}
	}
	return result;
}
// itemPrices comes ONLY from ItemClient.getPrices() -> {apiBase}/item/prices.js (4,681 entries, GE tradeables)

@Nonnull
public ItemComposition getItemComposition(int itemId)
{
	return client.getItemDefinition(itemId);  // no cache, no assert since commit e98c3da7; historically asserted isClientThread()
}

// ===== 2. GE panel threading shape (runelite-client/src/main/java/net/runelite/client/plugins/grandexchange/GrandExchangeSearchPanel.java)
// search bar action: executor.execute(() -> priceLookup(false));
// priceLookup: List<ItemPrice> result = itemManager.search(searchBar.getText());  // any thread
//              clientThread.invokeLater(() -> processResult(result, searchBar.getText(), exactMatch));
// processResult (CLIENT THREAD): itemManager.getItemComposition(itemId) / isGeTradeable() filter /
//              itemManager.getItemStats / itemManager.getImage -> then SwingUtilities.invokeLater(() -> UI);
// private static final int MAX_SEARCH_ITEMS = 100;

// ===== 3. Incumbent recipe search â€” EDT-synchronous, bundled index
// (github.com/KludensVogter/Recipes_Checklist @ b221cad7, src/main/java/com/recipechecklist/RecipeBook.java)
List<String> search(String query, int limit)
{
	final String needle = query.trim().toLowerCase();
	if (needle.isEmpty()) { return Collections.emptyList(); }
	final List<String> matches = new ArrayList<>();
	for (String name : byName.keySet())            // TreeMap(String.CASE_INSENSITIVE_ORDER-style), ~4.6k wiki names from bundled recipes.json
	{
		if (name.toLowerCase().contains(needle)) { matches.add(name); }
	}
	matches.sort((a, b) -> {
		int rankA = rank(a, needle); int rankB = rank(b, needle);
		if (rankA != rankB) { return Integer.compare(rankA, rankB); }
		if (a.length() != b.length()) { return Integer.compare(a.length(), b.length()); }
		return a.compareToIgnoreCase(b);
	});
	return matches.size() > limit ? matches.subList(0, limit) : matches;
}
private static int rank(String name, String needle)
{
	final String lower = name.toLowerCase();
	if (lower.equals(needle)) { return 0; }
	return lower.startsWith(needle) ? 1 : 2;
}

// ===== 4. Incumbent name-resolution/threading flow (src/main/java/com/recipechecklist/RecipeChecklistPlugin.java)
void refresh()
{
	if (panel == null) { return; }
	final Set<Integer> wanted = panel.trackedItemIds();
	if (wanted.isEmpty()) { return; }
	final BankMemory memory = bankMemory;
	final boolean countBank = config.includeBank() && memory != null;
	clientThread.invoke(() ->
	{
		final Map<Integer, Integer> inventory = tally(client.getItemContainer(InventoryID.INV));
		final Map<Integer, ItemCount> counts = new HashMap<>();
		for (int itemId : wanted)
		{
			counts.put(itemId, new ItemCount(
				inventory.getOrDefault(itemId, 0),
				countBank ? memory.count(itemId) : 0,
				itemManager.getItemComposition(itemId).getName()));   // CLIENT THREAD ONLY
		}
		SwingUtilities.invokeLater(() ->
		{
			if (panel != null) { panel.applyCounts(counts); }        // immutable snapshot to EDT
		});
	});
}
// ItemCount { final int inventory; final int bank; final String name; int total(); }

// ===== 5. recipes.json schema (wiki Bucket API generated; product NAMES bundled, ingredient names NOT)
// { "name": "'perfect' gold bar", "productId": 2365, "ingredients": [ { "itemId": 446, "quantity": 1 } ] }
// 87 entries have "productId": 0 (Construction/ship/no-item outputs) â€” representable only by name index.

// ===== 6. ClientThread contract (runelite-client/src/main/java/net/runelite/client/callback/ClientThread.java)
public void invoke(BooleanSupplier r)
{
	if (client.isClientThread())
	{
		if (!r.getAsBoolean()) { invokes.add(r); }
		return;
	}
	invokeLater(r);
}
public void invokeLater(BooleanSupplier r) { invokes.add(r); }  // drained each client frame

// ===== 7. gameval ItemID â€” names only in javadoc, not runtime (runelite-api/src/main/java/net/runelite/api/gameval/ItemID.java)
/** Bronze wire */    public static final int BRONZECRAFTWIRE = 1794;
/** Bronze hasta */   public static final int BRUT_BRONZE_SPEAR = 11367;
/** Longbow (u) */    public static final int UNSTRUNG_LONGBOW = 48;
/** */ // MOLTEN_GLASS = 1775, BOW_STRING = 1777

## Risks
- Client.getItemDefinition's javadoc documents NO thread requirement and the old client-thread assert was removed in 2021 (commit e98c3da7) â€” someone may argue it is now safe off-thread. There is no positive evidence of thread safety (the injected client source is closed), and every core call site still hops to the client thread, so the safe spec remains client-thread-only; but do not cite the javadoc as proof of the restriction.
- The 49.2% missing figure is measured against the incumbent's recipes.json (commit b221cad7, wiki data as of its generation date); the exact number drifts with game updates and includes variant products (poisoned/enchanted forms). The order of magnitude, not the exact count, is the load-bearing fact.
- prices.js content shifts as items become tradeable/untradeable and the endpoint is version-pinned (api.runelite.net/runelite-{version}/item/prices.js, version 1.12.38 today via static.runelite.net/bootstrap.json); any test asserting specific coverage will be brittle.
- itemManager.search returns results even while logged out (prices load on client start), but getItemComposition/getImage need the game cache â€” a client-thread name-resolution pass scheduled at plugin startUp before the cache is ready could stall or return placeholder data; the incumbent sidesteps this by only resolving names inside refresh() triggered by ItemContainerChanged (i.e., logged in). If you add an at-startup name pass, gate it on GameState.
- getName() vs getMembersName(): resolving material names on an F2P world with getName() yields 'Xyz (Members)' suffixes in the UI; incumbent uses getName() and inherits this cosmetic bug â€” use getMembersName() in the new plugin.
- The OSRS wiki Bucket API (oldschool.runescape.wiki/api.php, buckets infobox_item/infobox_construction/infobox_ship_part/recipe) is the data provenance for the bundled JSON; Bucket is a relatively new wiki subsystem and its schemas can change â€” pin the generator script's queries and keep the generated JSON in-repo so hub builds never hit the network (plugin-hub builds are offline/reproducible).
- RecipeBook.search runs O(n) on the EDT per keystroke â€” fine at ~4.6k names, but if the recipe set grows by an order of magnitude or per-keystroke work expands (icon prefetch), move it to the GE panel's executor pattern.
- On master the http-api ItemPrice now comes from external artifact net.runelite.arn:http-api:1.2.23 rather than an in-repo module; field-level details of ItemPrice beyond getId()/getName()/getPrice() (wiki price fields used via itemManager.getWikiPrice) were not source-verified because the artifact source is not on the runelite GitHub tree.
