# Gap follow-up: How are finished goods that are NOT items represented at runtime? Construction furniture and Sailing ship/boat parts â€” both explicitly in the plugin's planned scope â€” are scenery/objects with no item ID (the similar-plugins agent confirmed this is why recipe-checklist joins the infobox_construction and infobox_ship_part wiki buckets), yet every proposed data model keys products by int productId = ItemID, every icon path goes through itemManager.getImage(int itemId), search goes through item-name indexes, and persistence serializes item IDs. Nobody verified what identifier, icon source, display name, and wiki-link recipe-checklist or required-materials actually use at runtime for these ID-less products (e.g. negative/synthetic IDs? name-keyed entries? a representative item icon? SpriteManager sprites?). Concrete research: read the Recipe/Goal model and panel rendering code of KludensVogter/Recipes_Checklist and SchauweM/runelite-required-materials for the furniture/ship-part case, or make an explicit v1 descope decision (items-only products, Construction/Sailing excluded).

## How ID-less finished goods (Construction furniture, Sailing ship parts) are represented at runtime â€” settled with source evidence

Both reference plugins were read on GitHub `main` (Recipes_Checklist pushed 2026-08-25, runelite-required-materials pushed 2026-08-23). **Neither uses negative/synthetic IDs.** Both key products by **String name**, and each resolves the "no ItemID" problem differently. A third, critical discovery: *most* furniture and ship parts DO have real item IDs after all.

### Discovery 0 â€” the wiki gives real item IDs for most "scenery" products
The OSRS Wiki Bucket API (`https://oldschool.runescape.wiki/api.php?action=bucket&format=json&query=bucket('infobox_construction').select('page_name','item_id').limit(30).run()`) was queried live: **every sampled `infobox_construction` row and every `infobox_ship_part` row carries a populated `item_id` array** â€” e.g. `{"page_name":"Oak larder","item_id":[8234]}`, `{"page_name":"Exit portal","item_id":[8168]}`, `{"page_name":"Pond","item_id":[8170]}`, `{"page_name":"Adamant helm","item_id":[32152]}`, `{"page_name":"Bronze cannon","item_id":[32199]}`, `{"page_name":"Anchor (facility)","item_id":[32232]}`. These are the flatpack / build-menu-icon interface items (furniture ~8000s, Sailing parts ~32000s) â€” even non-flatpackable scenery like Pond/Exit portal has one, because the game's furniture-creation and ship-customisation interfaces render item icons. Recipes_Checklist's generator comment (tools/generate_recipes.py) states: "Construction furniture and ship parts are built, not items, so neither has an infobox_item rowâ€¦ The item id on these is the matching flatpack or part item, which is what we want for an icon." So joining those two buckets gives genuine `int productId` values usable with `itemManager.getImage(int)` for nearly everything; a residual minority still resolves to nothing (found in shipped recipes.json: "Abyssal demon head (mounted) (Gilded display)", "Aga resin (Conveyor belt (Mastering Mixology))", "Anvil (amenity)" â€” all productId 0/absent).

### Strategy A â€” KludensVogter/Recipes_Checklist (static recipe book, name-primary)
- Model (`src/main/java/com/recipechecklist/Recipe.java`): `public String name; public int productId; List<Ingredient> ingredients; int makes;`. `productId == 0` means "built, not held". Generator writes `productId: 0` rather than dropping the recipe.
- **Primary key is the name, everywhere.** `RecipeBook` indexes `Map<String,Recipe> byName` (TreeMap, CASE_INSENSITIVE_ORDER) and only additionally `Map<Integer,List<Recipe>> byProductId` for `productId > 0` ("Scenery you build has no item behind it and so no product id" â€” verbatim comment). Search (`search(String query,int limit)`) iterates `byName.keySet()` â€” pure name substring, so ID-less products are fully searchable.
- **Icon**: `Recipe.iconItemId()` returns `productId` when `> 0`, else **the first ingredient's itemId** ("borrow the first material's icon instead of showing a blank"). All icons go through `itemManager.getImage(recipe.iconItemId()).addTo(jlabel)` (AsyncBufferedImage). No SpriteManager, no object models.
- **Display name**: always `recipe.name` (the wiki page/variant name, e.g. "Adamant helm (Raft)"), never `ItemComposition.getName()` for products. (Materials rows DO update their label from `itemManager.getItemComposition(id).getName()` on the client thread.)
- **Wiki link**: `itemId > 0 ? "https://oldschool.runescape.wiki/w/Special:Lookup?type=item&id="+itemId : "https://oldschool.runescape.wiki/w/Special:Search?search="+URLEncoder.encode(name)` â€” name-search fallback for ID-less products.
- **Persistence** (`Goal.java`): goals serialize as flat text lines `depth\tamount\tcollapsed\tname` into ConfigManager (group key "goals") â€” **name-keyed, no product ID persisted at all**, so ID-less products round-trip trivially; rehydration is `recipeBook.get(goal.name)`.
- **Drill-down**: clicking a material row calls `recipeBook.simplestFor(ingredient.itemId)` (byProductId index) and adds a child Goal by `subRecipe.name`. Aggregation (`collectMaterials`) recurses the Goal tree; `childCovering` matches `ingredient.allIds().contains(childRecipe.productId)` â€” drill-down targets are always items, only checklist ROOTS can be ID-less scenery, so the asymmetry is safe.
- Adding from game: watches `MenuOptionClicked` on `net.runelite.api.gameval.InterfaceID.{SKILL_GUIDE, SKILL_GUIDE_V2, POH_FURNITURE_CREATION, POH_FURNITURE_CREATION_MENU, SAILING_CUSTOMISATION, SAILING_BOAT_SELECTION, SAILING_BT_SELECTION, SAILING_MENU}`; resolves via `event.getItemId()` / `widget.getItemId()` first (works because build menus carry those interface item ids!), falling back to name matching `recipeBook.bestFor(Text.removeTags(event.getMenuTarget()))` with variant expansion "Name (" prefix and a unique-all-words-match last resort.

### Strategy B â€” SchauweM/runelite-required-materials (live wiki fetch, name-only)
- Model: `TrackedRequirement { String partName; List<RequiredMaterial> materials; List<String> levelRequirements; List<String> prerequisites; String skill; }` keyed by partName in `MaterialsManager`'s `LinkedHashMap<String,TrackedRequirement>`. `RequiredMaterial { String name; int quantity; Integer itemId; }` â€” **itemId is a nullable, derived cache**, resolved from the name via `itemManager.search(name)` (exact-case-insensitive match over `List<ItemPrice>`), then a lazily-built full `client.getItemDefinition(id)` nameâ†’id index scan, then first-search-result fallback, else null with log warning "won't be highlightable in the bank".
- **The product (part) itself never has an ID of any kind.** No icon is rendered for parts or materials â€” the panel is text rows; only `SkillIconManager.getSkillImage(skill,true)` for the Sailing/Construction accordion headers. A material with null itemId renders as text in `ColorScheme.PROGRESS_ERROR_COLOR` with "(couldn't find a matching item)".
- **Recipes fetched at runtime** from `https://oldschool.runescape.wiki/api.php?action=parse&prop=wikitext&format=json&redirects=true&page=<PageName>` (OkHttpClient, custom User-Agent "RequiredMaterials-RuneLite-Plugin/1.0"), parsing `{{Recipe}}` templates (`mat1..mat10`, `matNquantity`, `matNcost==0` â‡’ prerequisite, `skill1/skill1lvl`, `output1subtxt` names boat-size variants). Sailing page candidates: `[name, name + " (facility)"]`.
- **Object IDs appear only for detecting BUILT furniture**, never as product keys: `WikiRecipeService.parseObjectIds` regex `^\|\s*id\d*\s*=\s*([0-9,\s]+)$` (MULTILINE) pulls infobox object ids from wikitext; `HouseContents` scans all four tile-object kinds in the POH scene (gated on `VarbitID.POH_BUILDING_MODE != 0`) and intersects seen object ids with the per-furniture-name id sets, persisted per account.
- **Persistence**: Gson map `partName â†’ {materials:[{name,quantity}], levelRequirements, skill, prerequisites}` â€” quantities and NAMES only; itemIds re-resolved on every load.

### Verdict for our plugin
No descope needed. The proven pattern: (1) make **recipe/product name the primary key** for model, search, persistence, and drill-down child matching; (2) keep `int productId` as an optional (`0` = none) adornment used only for icon, wiki Special:Lookup link, and byProductId reverse lookup; (3) join `infobox_construction` + `infobox_ship_part` buckets (page_nameâ†’item_id) into the nameâ†’id resolution so ~all furniture/ship parts get a real interface-item id and a real ItemManager icon; (4) fall back to first-ingredient icon and name-based wiki search for the residual `productId == 0` entries; (5) never persist by product id â€” persist names (both plugins survived the exact schema hazard we feared this way).

## Key facts
- Neither reference plugin uses negative or synthetic product IDs; both key products by String name (Recipes_Checklist: Recipe.name / Goal.name; required-materials: TrackedRequirement.partName).
- OSRS Wiki buckets infobox_construction and infobox_ship_part expose an item_id for (nearly) every buildable â€” the flatpack or build-menu interface item (furniture ~8000s: Oak larder=8234, Exit portal=8168, Pond=8170; Sailing parts ~32000s: Adamant helm=32152, Bronze cannon=32199, Anchor (facility)=32232) â€” verified by live query against https://oldschool.runescape.wiki/api.php?action=bucket.
- Recipes_Checklist merges those bucket rows into its name-to-itemId resolution in tools/generate_recipes.py, so furniture/ship-part recipes end up with real productId > 0 (e.g. 'Adamant helm (Raft)' productId=32152 in shipped recipes.json); only a residue gets productId=0 (found: 'Abyssal demon head (mounted) (Gilded display)', 'Aga resin (Conveyor belt (Mastering Mixology))', 'Anvil (amenity)').
- Recipe.iconItemId() in Recipes_Checklist returns productId when >0, else the FIRST INGREDIENT's itemId ('borrow the first material's icon instead of showing a blank'); every icon then goes through itemManager.getImage(int).addTo(JLabel) â€” no SpriteManager, no object rendering.
- Wiki link fallback in RecipeChecklistPanel: productId>0 uses https://oldschool.runescape.wiki/w/Special:Lookup?type=item&id=<id>; productId==0 uses https://oldschool.runescape.wiki/w/Special:Search?search=<urlencoded name>, opened via net.runelite.client.util.LinkBrowser.browse(url).
- Recipes_Checklist persistence stores NO product IDs: Goal.serialize writes one line per goal 'depth\tamount\tcollapsed\tname' (tab-separated) via ConfigManager key 'goals'; rehydration is recipeBook.get(name). ID-less products therefore persist identically to items.
- RecipeBook indexes Map<String,Recipe> byName (TreeMap CASE_INSENSITIVE_ORDER, ~4000 entries from bundled recipes.json) plus Map<Integer,List<Recipe>> byProductId only for productId>0; search() is name-substring over byName, so scenery products are searchable with zero item-index involvement.
- Drill-down in Recipes_Checklist: material row click resolves recipeBook.simplestFor(ingredient.itemId) and adds a child Goal by recipe NAME; aggregation matches child coverage via ingredient.allIds().contains(childRecipe.productId). Drill-down targets are always items â€” only checklist roots can be ID-less scenery â€” so the byProductId lookup never needs to handle furniture.
- required-materials represents the part purely as a String partName with no ID ever; per-material Integer itemId is a nullable derived cache resolved name-first via itemManager.search(String) (List<ItemPrice>, exact case-insensitive match), then a full client.getItemDefinition(id) name index scan on the client thread, then first search result, else null (material shown as error-colored text '(couldn't find a matching item)').
- required-materials renders NO item icons at all â€” text-only panel rows; only SkillIconManager.getSkillImage(skill, true) for the Sailing/Construction accordion headers.
- required-materials fetches recipes at runtime from the wiki parse API (action=parse&prop=wikitext&redirects=true&page=), parsing {{Recipe}} template params mat1..mat10 / matNquantity / matNcost==0-as-prerequisite / skill1lvl / output1subtxt (boat-size variants); Sailing page candidates are [name, name+' (facility)'].
- Object IDs (scenery LOC ids) are used ONLY for detecting already-built furniture: WikiRecipeService.parseObjectIds regex '^\|\s*id\d*\s*=\s*([0-9,\s]+)$' over infobox wikitext; HouseContents scans all four tile-object kinds while VarbitID.POH_BUILDING_MODE != 0 and intersects with those ids. Object IDs are never product keys, icons, or persistence keys.
- required-materials persistence is a Gson JSON map partName -> {materials:[{name,quantity}], levelRequirements, skill, prerequisites} in ConfigManager; itemIds are deliberately NOT persisted and are re-resolved on load.
- Both plugins use current gameval constants: net.runelite.api.gameval.InventoryID (InventoryID.INV, InventoryID.BANK as int container ids) and net.runelite.api.gameval.InterfaceID including POH_FURNITURE_CREATION, POH_FURNITURE_CREATION_MENU, SAILING_CUSTOMISATION, SAILING_BOAT_SELECTION, SAILING_BT_SELECTION, SAILING_MENU, SKILL_GUIDE, SKILL_GUIDE_V2.
- In-game add flow works for scenery because the furniture-creation and sailing-customisation interfaces themselves carry the interface item ids: RecipeChecklistPlugin tries event.getItemId() / client.getWidget(component).getItemId() first, then falls back to name matching on Text.removeTags(event.getMenuTarget()) via bestFor() (exact -> 'Name (' variant prefix -> unique all-words-contained match).
- Owned counts count both inventory and bank: tally() canonicalizes with itemManager.canonicalize(item.getId()) over client.getItemContainer(InventoryID.INV), bank via a BankMemory snapshot; per-material color rules: green=inventory covers, white=inv+bank covers, yellow=shortfall craftable from stock, red=insufficient.
- V1 decision recommended by the evidence: no descope of Construction/Sailing needed IF products are name-keyed with optional productId adornment; a product-ID-primary schema would break exactly at the ~dozens of productId==0 residual buildables and at persistence.

## Code patterns
From https://raw.githubusercontent.com/KludensVogter/Recipes_Checklist/main/src/main/java/com/recipechecklist/Recipe.java:
```java
public class Recipe {
    public String name;
    public int productId;            // 0 == built scenery, no item
    public List<Ingredient> ingredients;
    public int makes;                // absent/0 -> batchSize()=1
    int iconItemId() {
        if (productId > 0) return productId;
        return ingredients == null || ingredients.isEmpty() ? 0 : ingredients.get(0).itemId;
    }
    public static class Ingredient { public int itemId; public int quantity; public List<Integer> same; }
}
```
Icon rendering (RecipeChecklistPanel.java): `itemManager.getImage(recipe.iconItemId()).addTo(iconLabel);` â€” net.runelite.client.game.ItemManager#getImage(int) -> AsyncBufferedImage#addTo(JLabel).

Wiki link fallback (RecipeChecklistPanel.java):
```java
final String url = itemId > 0
    ? "https://oldschool.runescape.wiki/w/Special:Lookup?type=item&id=" + itemId
    : "https://oldschool.runescape.wiki/w/Special:Search?search=" + encode(name);
// opened with net.runelite.client.util.LinkBrowser.browse(url)
```

Name-primary indexing (RecipeBook.java):
```java
private final Map<String, Recipe> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
private final Map<Integer, List<Recipe>> byProductId = new HashMap<>();
// on load: if (recipe.productId > 0) byProductId.computeIfAbsent(...)  // "Scenery you build has no item behind it and so no product id."
Recipe simplestFor(int itemId)   // drill-down entry: fewest ingredient kinds, ties -> more raw material
Recipe bestFor(String productName) // exact -> variantsOf(name + " (") -> unique all-words match
```

Persistence with no IDs (Goal.java): serialize one line per goal `depth + "\t" + amount + "\t" + (collapsed?1:0) + "\t" + name`, stored via `configManager.setConfiguration("recipechecklist-ish GROUP", "goals", serialized)`; parse rebuilds tree from depth.

Generator bucket join (tools/generate_recipes.py):
```python
for bucket in ("infobox_construction", "infobox_ship_part"):
    for row in fetch_all(bucket, ["page_name", "item_id"]):  # item_id = flatpack / build-menu part item
# unresolved products: product_id = 0; recipe kept, panel falls back to ingredient icon
```
Live-verified bucket rows: `{"page_name":"Oak larder","item_id":[8234]}`, `{"page_name":"Adamant helm","item_id":[32152]}` via `api.php?action=bucket&format=json&query=bucket('infobox_construction').select('page_name','item_id').limit(30).run()`.

From https://raw.githubusercontent.com/SchauweM/runelite-required-materials/main/src/main/java/com/requiredmaterials/RequiredMaterial.java:
```java
@Data public class RequiredMaterial {
    private final String name; private final int quantity;
    private Integer itemId;  // nullable, resolved separately via ItemManager; null -> error-colored text row
}
```
TrackedRequirement.java: `private final String partName; private final List<RequiredMaterial> materials; private List<String> levelRequirements; private List<String> prerequisites; private String skill;` â€” keyed by partName.

Name->id resolution (MaterialsManager.java):
```java
private Integer resolveItemId(String itemName) {
    List<ItemPrice> results = itemManager.search(itemName);
    for (ItemPrice r : results) if (r.getName().equalsIgnoreCase(itemName)) return r.getId();
    if (client.isClientThread()) { Integer m = fullItemNameIndex().get(itemName.toLowerCase()); if (m != null) return m; }
    if (!results.isEmpty()) return results.get(0).getId();
    return null;
}
// fullItemNameIndex: for (int id = 0; id < MAX_ITEM_ID_SCAN; id++) client.getItemDefinition(id).getName()
```

Runtime wiki recipe fetch (WikiRecipeService.java):
```java
private static final String API_URL = "https://oldschool.runescape.wiki/api.php?action=parse&prop=wikitext&format=json&redirects=true&page=";
private static final Pattern OBJECT_ID_PATTERN = Pattern.compile("^\\|\\s*id\\d*\\s*=\\s*([0-9,\\s]+)$", Pattern.MULTILINE);
// {{Recipe}} params: mat1..mat10, matNquantity, matNcost=="0" -> prerequisite (built thing), skill1/skill1lvl, output1subtxt = variant name
```

Current API usage confirmed in both plugins' imports: `net.runelite.api.gameval.InventoryID` (InventoryID.INV / InventoryID.BANK as ints compared against ItemContainerChanged.getContainerId()), `net.runelite.api.gameval.InterfaceID` (POH_FURNITURE_CREATION, SAILING_CUSTOMISATION, SAILING_BOAT_SELECTION, SAILING_BT_SELECTION, SAILING_MENU, SKILL_GUIDE, SKILL_GUIDE_V2), `net.runelite.api.widgets.WidgetUtil.componentToInterface(int)`, `itemManager.canonicalize(int)`, `itemManager.getItemComposition(int).getName()` on ClientThread.

## Risks
- WebFetch returns model-extracted quotes of the raw files; while the prompt demanded verbatim output, minor transcription drift is possible â€” re-verify exact lines against the raw URLs before copying signatures into code.
- Bucket sampling was 30 rows (infobox_construction) and 13 rows (infobox_ship_part sample); all had item_id, but 100% coverage is not guaranteed â€” the shipped recipes.json proves residual productId==0 entries exist ('Anvil (amenity)', gilded-display heads, Mastering Mixology conveyor outputs), so the first-ingredient-icon + name-search-wiki-link fallback is still required.
- The 8xxx/32xxx 'product' item ids are unobtainable interface/flatpack items: fine for itemManager.getImage and Special:Lookup, but they must never be counted against bank/inventory (both plugins only count MATERIAL ids, never product ids â€” preserve that separation).
- itemManager.getImage on an interface item relies on the item having a cache sprite; all sampled build-menu items do (the game renders them in its own menus), but a future Jagex change to icon-less internal items would blank icons for those entries.
- Name-keyed persistence means wiki page renames (or recipes.json regeneration changing variant suffixes like '(Raft)' vs '(Total)') orphan saved goals; Recipes_Checklist mitigates by tolerant parsing and keeping older serialization formats â€” plan the same.
- RecipeBook drops name-colliding recipes at load ('N recipes share a name and were discarded') â€” a name-primary design must enforce unique names at generation time.
- required-materials' full item-name index scans client.getItemDefinition(id) for id in [0, MAX_ITEM_ID_SCAN) on the client thread â€” the constant's value was not captured; a full scan is expensive and must stay off the EDT and be cached.
- InterfaceID.SAILING_* / POH_FURNITURE_CREATION constants were verified only via these plugins' imports compiling against current runelite-api (both repos pushed Aug 2026), not against runelite master directly; confirm exact constant names in net.runelite.api.gameval.InterfaceID when writing code.
- Recipes_Checklist's small-model extraction reported '~1,000+' recipes.json entries while the RecipeBook javadoc says ~4,000 â€” the JSON fetch was likely truncated; do not rely on the entry examples being exhaustive.
- required-materials' live wiki fetching requires an OkHttpClient with a custom User-Agent and is subject to wiki template drift ({{Recipe}} param names) â€” the static-generation approach (Recipes_Checklist) isolates that risk to a build-time script, which is the safer pattern for Plugin Hub review.
