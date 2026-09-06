# Research: containers

# Tracking owned items (inventory + bank) in the current RuneLite API (verified against master, 2026-09-05)

## 1. Container IDs: use `net.runelite.api.gameval.InventoryID` (the old enum is @Deprecated)

The old `net.runelite.api.InventoryID` **enum** still exists but is `@Deprecated` at class level (constants `INVENTORY(93)`, `BANK(95)`, accessor `public int getId()`). The current replacement is the generated final class `net.runelite.api.gameval.InventoryID` holding plain `public static final int` constants:

- `INV = 93` (player backpack)
- `WORN = 94` (equipment)
- `BANK = 95`
- `SEED_VAULT = 626`
- `BANK_HOLDINGINV = 572`, `RAIDS_SHAREDSTORAGE = 582`, `RAIDS_PRIVATESTORAGE = 583`

`Client` (package `net.runelite.api`, `public interface Client extends OAuthApi, GameEngine` where OAuthApi is `com.jagex.oldscape.pub.OAuthApi`) has two overloads, both `@Nullable`:

```java
@Nullable ItemContainer getItemContainer(InventoryID inventory); // legacy enum overload; javadoc: @see net.runelite.api.gameval.InventoryID
@Nullable ItemContainer getItemContainer(int id);                // current style: client.getItemContainer(InventoryID.INV) with the gameval int
```

Core plugins now do `client.getItemContainer(InventoryID.BANK)` with the gameval int import (verified in `runelite-client/.../plugins/bank/BankPlugin.java`, which imports `net.runelite.api.gameval.InventoryID`, `...gameval.InterfaceID`, `...gameval.ItemID`, `...gameval.VarbitID`).

## 2. `ItemContainer` interface (`net.runelite.api`, extends `Node`)

```java
int getId();
@Nonnull Item[] getItems();      // length = highest used slot+1; empty slots have id -1
@Nullable Item getItem(int slot);
boolean contains(int itemId);
int count(int itemId);
int size();
```
`net.runelite.api.Item` carries `getId()` / `getQuantity()`.

## 3. `ItemContainerChanged` event

`net.runelite.api.events.ItemContainerChanged`, Lombok `@Value` with fields `int containerId` and `ItemContainer itemContainer` (getters `getContainerId()`, `getItemContainer()`). Javadoc: fires "whenever the stack size of an Item in an ItemContainer is modified" â€” e.g. withdrawing from bank fires for BOTH bank and inventory containers, picking up, dropping. It is delivered on the client thread via the EventBus; do container/ItemComposition work in the handler or via `ClientThread`.

Canonical handler shape (from core BankPlugin):
```java
@Subscribe
public void onItemContainerChanged(ItemContainerChanged event) {
    int containerId = event.getContainerId();
    if (containerId == InventoryID.BANK) { /* gameval int compare */ }
}
```

## 4. When is the bank readable?

- `client.getItemContainer(InventoryID.BANK)` is **null until the player opens the bank once that session**; after that it holds the **last-seen** contents (it is NOT live-updated while the bank is closed). The Bank Memory plugin README states explicitly: "You must log in to an account and open the bank for the plugin to be able to actually get the data."
- An `ItemContainerChanged` with `containerId == InventoryID.BANK (95)` fires when the bank is opened and on every change while it is open â€” this is THE hook for snapshotting.
- Bank-open UI detection: core BankPlugin sets `bankOpen = true` in `onWidgetLoaded` when `event.getGroupId() == InterfaceID.BANKMAIN`. gameval `net.runelite.api.gameval.InterfaceID`: `BANKMAIN = 12`, `BANKSIDE = 15` (bank-side inventory interface), `INVENTORY = 149`. Nested component constants exist: `InterfaceID.Bankmain.ITEMS = 0x000c_000c`, `Bankmain.SCROLLBAR = 0x000c_000d`, `Bankmain.TABS = 0x000c_000a`, `Bankmain.CAPACITY = 0x000c_0008`, `Bankmain.WORNITEMS_CONTAINER = 0x000c_003d`. `net.runelite.api.events.WidgetClosed` (`@Value`) has `groupId` (@Interface), `modalMode`, `unload` for detecting bank close.

## 5. Placeholders, bank fillers, noted items, stackables

In the **bank container**, placeholders appear as the placeholder item id with **quantity 0**. Core BankPlugin's value calculation simply skips `if (id <= 0 || qty == 0) continue;` and separately excludes `ItemID.BANK_FILLER` (gameval ItemID) when building quantity maps.

`net.runelite.api.ItemComposition` (obtain via `ItemManager.getItemComposition(int)` â€” client thread only):
```java
int getNote();                  // 799 if noted, -1 otherwise
int getLinkedNoteId();          // noted<->unnoted counterpart id
int getPlaceholderTemplateId(); // 14401 if placeholder, -1 otherwise
int getPlaceholderId();         // normal<->placeholder counterpart id
boolean isStackable();
```

`net.runelite.client.game.ItemManager` (`@Singleton`, injectable) â€” canonicalization used by virtually all hub plugins:
```java
public int canonicalize(int itemID) {
    ItemComposition itemComposition = getItemComposition(itemID);
    if (itemComposition.getNote() != -1) return itemComposition.getLinkedNoteId();
    if (itemComposition.getPlaceholderTemplateId() != -1) return itemComposition.getPlaceholderId();
    return WORN_ITEMS.getOrDefault(itemID, itemID); // also maps worn/degraded variants to base
}
```
Also: `@Nonnull ItemComposition getItemComposition(int itemId)`, `int getItemPrice(int itemID)`.

So: canonicalize every id; a **placeholder** is detectable either by `qty == 0` in the bank container or by `canonicalize(id) != id && composition.getPlaceholderTemplateId() != -1`; **noted** bank/inventory items canonicalize to the unnoted id so quantities aggregate correctly.

## 6. Bank Memory hub plugin â€” exactly how it captures & stores bank state

Manifest `plugin-hub/plugins/bank-memory` â†’ repo **https://github.com/Lazyfaith/runelite-bank-memory-plugin** (pinned commit `3174826dadc0a2207fe1ef60ce6a7f6385b5750b`). Package `com.bankmemory`, data layer in `com.bankmemory.data`.

**Capture** (`BankMemoryPlugin.onItemContainerChanged`):
```java
if (event.getContainerId() != InventoryID.BANK.getId()) return; // (old enum style)
BankWorldType worldType = BankWorldType.forWorld(client.getWorldType());
ItemContainer bank = event.getItemContainer();
String accountIdentifier = AccountIdentifier.fromAccountHash(client.getAccountHash());
dataStore.saveAsCurrentBank(BankSave.fromCurrentBank(worldType, accountIdentifier, bank, itemManager));
```
Also `onGameTick` registers the player's display name into a name map (account hash -> display name) and `onGameStateChanged` resets that flag on logout.

**Filtering** (`BankSave.fromCurrentBank`): iterates `bank.getItems()`; for each item computes the canonical id via ItemManager; `if (idInBank != canonId) { /* just a placeholder */ }` skips placeholders; `isItemToClean(id)` skips `NULL_ITEM_ID (-1)` and `ItemID.BANK_FILLER`. Stored fields: `long id`, `BankWorldType worldType`, `String accountIdentifier`, `@Nullable String saveName`, `String dateTimeString`, `ImmutableList<BankItem> itemData` (itemId + quantity pairs).

**Persistence** (`PluginDataStore` + `ConfigReaderWriter`): plain ConfigManager string config, NOT RSProfile-scoped â€” per-account scoping is done by embedding the account hash in the JSON:
```java
private static final String PLUGIN_BASE_GROUP = "bankMemory";
// keys: "currentList", "snapshotList", "nameMap"
String jsonString = configManager.getConfiguration(PLUGIN_BASE_GROUP, configKey);
configManager.setConfiguration(write.configGroup, write.configKey, gson.toJson(write.data));
```
Gson uses a custom `ItemDataParser` TypeAdapter that serializes the item list as a compact CSV string of `itemId,quantity,` pairs (halves JSON bloat). Writes are queued (`configWritesQueue.put(...)`) and flushed by a dedicated daemon `Thread(new ConfigWriter(), "Bank Memory config writer")` so config I/O is off the client thread. Accounts are keyed by `client.getAccountHash()` (`AccountIdentifier`), with a separate persisted display-name map, and saves are split by `BankWorldType` (regular/League/DMM/etc. via `client.getWorldType()`).

## 7. Per-RS-account scoping the modern way: ConfigManager RSProfile config

`net.runelite.client.config.ConfigManager` (`net.runelite.client.config`):
```java
public static final String RSPROFILE_GROUP = "rsprofile";
public String getRSProfileKey();                                            // null until a profile is resolved (login)
public String getRSProfileConfiguration(String groupName, String key);      // returns null if rsProfileKey == null
public <T> T getRSProfileConfiguration(String groupName, String key, Type clazz);
public <T> void setRSProfileConfiguration(String groupName, String key, T value);
public void unsetRSProfileConfiguration(String groupName, String key);
```
Internally values are stored as `groupName + "." + rsProfileKey + "." + key`. The profile is resolved by `findRSProfile(getRSProfiles(), client.getAccountHash(), RuneScapeProfileType.getCurrent(client), displayName, create)` â€” i.e. keyed on **account hash + world/profile type** (standard vs league vs DMM), not display name (display name is stored as metadata). `setRSProfileConfiguration` lazily creates the profile if logged in; if not logged in it logs "trying to create a profile while not logged in" and no-ops. On profile change ConfigManager posts `RuneScapeProfileChanged` (`net.runelite.client.events`). `client.getAccountHash()` comes from `com.jagex.oldscape.pub.OAuthApi`: `long getAccountHash()` â€” "unique per-RuneScape-Account identifier or -1 if the client has not logged in yet".

**Recommended pattern for Material Checklist**: on `ItemContainerChanged` for `InventoryID.BANK`, canonicalize + filter (skip id<=0, qty==0, BANK_FILLER), serialize a compact id,qty list with the injected `Gson`, and `configManager.setRSProfileConfiguration("materialchecklist", "bankSnapshot", json)`. Read it back at startup/on `RuneScapeProfileChanged` with `getRSProfileConfiguration`. Inventory (`InventoryID.INV`) is always live while logged in, so owned = live inventory count + persisted bank snapshot count, both keyed by canonical item id.

## 8. Gradle (for completeness)

example-plugin `build.gradle`: repo `maven { url = 'https://repo.runelite.net' }` (+ mavenCentral), dependency `compileOnly group: 'net.runelite', name: 'client', version: 'latest.release'`, Lombok 1.18.30 compileOnly + annotationProcessor.

## Key facts
- Current inventory/bank container ids live in the generated class net.runelite.api.gameval.InventoryID as plain ints: INV=93 (backpack), WORN=94, BANK=95, SEED_VAULT=626; the old net.runelite.api.InventoryID enum (INVENTORY(93), BANK(95), getId()) is @Deprecated at class level.
- Client has @Nullable ItemContainer getItemContainer(int id) (use with gameval InventoryID ints) plus a legacy @Nullable getItemContainer(InventoryID) enum overload; both javadocs point at net.runelite.api.gameval.InventoryID.
- ItemContainer (net.runelite.api): int getId(); @Nonnull Item[] getItems(); @Nullable Item getItem(int slot); boolean contains(int itemId); int count(int itemId); int size(). Item exposes getId()/getQuantity().
- net.runelite.api.events.ItemContainerChanged is a Lombok @Value event with int containerId and ItemContainer itemContainer; fires whenever a stack size in a container changes (bank withdrawal fires for BOTH bank and inventory), delivered on the client thread.
- The bank ItemContainer is null until the bank has been opened once in the session, and after closing it retains the last-seen (stale) contents; ItemContainerChanged with containerId==95 fires on bank open and on every change while open â€” snapshot there. Bank Memory README: 'You must log in to an account and open the bank for the plugin to be able to actually get the data.'
- Bank-open detection in core BankPlugin: onWidgetLoaded event.getGroupId() == net.runelite.api.gameval.InterfaceID.BANKMAIN (=12); BANKSIDE=15, INVENTORY interface=149; component constants under InterfaceID.Bankmain (e.g. ITEMS=0x000c_000c). WidgetClosed (net.runelite.api.events) has groupId/modalMode/unload for close detection.
- Bank placeholders appear in the bank container with quantity 0; core BankPlugin skips them with 'if (id <= 0 || qty == 0) continue;' and also excludes gameval ItemID.BANK_FILLER. ItemComposition.getPlaceholderTemplateId() returns 14401 for placeholders (-1 otherwise) and getPlaceholderId() maps placeholder<->normal.
- Noted items: ItemComposition.getNote() returns 799 if noted (-1 otherwise); getLinkedNoteId() maps noted<->unnoted. net.runelite.client.game.ItemManager.canonicalize(int) resolves noted->unnoted, placeholder->normal, and worn/degraded variants->base via a WORN_ITEMS map; use it on every id before aggregating. ItemComposition.isStackable() indicates inventory stackability. getItemComposition is @Nonnull and must be called on the client thread.
- Bank Memory hub plugin = github.com/Lazyfaith/runelite-bank-memory-plugin (manifest plugin-hub/plugins/bank-memory, commit 3174826dadc0a2207fe1ef60ce6a7f6385b5750b). It snapshots the bank in onItemContainerChanged when containerId==BANK, building BankSave.fromCurrentBank(worldType, accountIdentifier, bank, itemManager); placeholders detected by canonicalize(id)!=id, and -1/BANK_FILLER skipped.
- Bank Memory persists via ConfigManager string config under group "bankMemory", keys "currentList", "snapshotList", "nameMap"; Gson with a custom ItemDataParser TypeAdapter serializes items as a CSV string of itemId,quantity pairs; writes go through a BlockingQueue drained by a dedicated daemon thread ('Bank Memory config writer'). Per-account scoping is manual: account hash (client.getAccountHash()) embedded in the JSON, plus a persisted accountHash->displayName map and per-BankWorldType (client.getWorldType()) separation.
- The modern per-RS-account persistence mechanism is ConfigManager RSProfile config: getRSProfileConfiguration(String groupName, String key) [+ Type overload], setRSProfileConfiguration(String groupName, String key, T value), unsetRSProfileConfiguration(String, String), getRSProfileKey(), constant RSPROFILE_GROUP="rsprofile". Stored as groupName+"."+rsProfileKey+"."+key.
- RSProfiles are matched by client.getAccountHash() + RuneScapeProfileType.getCurrent(client) (standard/league/DMM), NOT display name; getRSProfileConfiguration returns null and setRSProfileConfiguration no-ops (warn log) when not logged in; ConfigManager posts RuneScapeProfileChanged (net.runelite.client.events) when the active profile changes.
- com.jagex.oldscape.pub.OAuthApi.getAccountHash() returns long: 'unique per-RuneScape-Account identifier or -1 if the client has not logged in yet'; Client extends OAuthApi, GameEngine.
- Recommended Material Checklist pattern: live-read InventoryID.INV on ItemContainerChanged; on containerId==InventoryID.BANK canonicalize+filter (id<=0, qty==0, BANK_FILLER) and persist a compact itemId,qty list via setRSProfileConfiguration; owned quantity = live inventory count + persisted bank snapshot count keyed by canonical id.
- Gradle: compileOnly group 'net.runelite', name 'client', version 'latest.release' from maven repo https://repo.runelite.net (example-plugin build.gradle), Lombok 1.18.30 compileOnly+annotationProcessor.

## Code patterns
// --- gameval constants (net/runelite/api/gameval/InventoryID.java, master) ---
package net.runelite.api.gameval;
public final class InventoryID {
    public static final int INV = 93;         // backpack
    public static final int WORN = 94;        // equipment
    public static final int BANK = 95;
    public static final int SEED_VAULT = 626;
}
// old net.runelite.api.InventoryID is @Deprecated: INVENTORY(93), BANK(95), public int getId()

// --- Client (runelite-api/.../api/Client.java) ---
@Nullable ItemContainer getItemContainer(int id);              // pass gameval InventoryID.INV / .BANK
@Nullable ItemContainer getItemContainer(InventoryID inventory); // legacy enum overload
// com.jagex.oldscape.pub.OAuthApi (runelite-api/.../com/jagex/oldscape/pub/OAuthApi.java):
long getAccountHash(); // -1 if not logged in

// --- ItemContainer (net.runelite.api) ---
@Nonnull Item[] getItems(); @Nullable Item getItem(int slot); int count(int itemId); int size();

// --- core BankPlugin (runelite-client/.../plugins/bank/BankPlugin.java) â€” canonical patterns ---
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
@Subscribe public void onItemContainerChanged(ItemContainerChanged event) {
    int containerId = event.getContainerId();
    if (containerId == InventoryID.BANK) { itemQuantities = null; }
}
// placeholder/empty filtering when summing a container:
for (final Item item : items) {
    final int qty = item.getQuantity(); final int id = item.getId();
    if (id <= 0 || qty == 0) { continue; }        // empty slots and bank placeholders
}
if (item.getId() != ItemID.BANK_FILLER) { set.add(item.getId(), item.getQuantity()); }
// bank open: onWidgetLoaded â†’ if (event.getGroupId() == InterfaceID.BANKMAIN) { bankOpen = true; }
// gameval InterfaceID: BANKMAIN=12, BANKSIDE=15, INVENTORY=149; InterfaceID.Bankmain.ITEMS=0x000c_000c

// --- ItemManager.canonicalize (runelite-client/.../client/game/ItemManager.java) ---
public int canonicalize(int itemID) {
    ItemComposition itemComposition = getItemComposition(itemID);
    if (itemComposition.getNote() != -1) return itemComposition.getLinkedNoteId();
    if (itemComposition.getPlaceholderTemplateId() != -1) return itemComposition.getPlaceholderId();
    return WORN_ITEMS.getOrDefault(itemID, itemID);
}
// ItemComposition: getNote()==799 if noted else -1; getPlaceholderTemplateId()==14401 if placeholder else -1; isStackable()

// --- Bank Memory plugin (github.com/Lazyfaith/runelite-bank-memory-plugin @ 3174826) ---
// BankMemoryPlugin.onItemContainerChanged:
if (event.getContainerId() != InventoryID.BANK.getId()) return;
BankWorldType worldType = BankWorldType.forWorld(client.getWorldType());
String accountIdentifier = AccountIdentifier.fromAccountHash(client.getAccountHash());
dataStore.saveAsCurrentBank(BankSave.fromCurrentBank(worldType, accountIdentifier, event.getItemContainer(), itemManager));
// BankSave.fromCurrentBank: skips placeholders via (idInBank != canonId), skips -1 and ItemID.BANK_FILLER
// ConfigReaderWriter: group "bankMemory", keys "currentList"/"snapshotList"/"nameMap";
String jsonString = configManager.getConfiguration(PLUGIN_BASE_GROUP, configKey);
configManager.setConfiguration(write.configGroup, write.configKey, gson.toJson(write.data));
// item list serialized by custom Gson TypeAdapter as CSV "itemId,qty,itemId,qty,..."; writes drained by daemon thread

// --- ConfigManager RSProfile API (runelite-client/.../client/config/ConfigManager.java) ---
public static final String RSPROFILE_GROUP = "rsprofile";
public String getRSProfileKey();
public String getRSProfileConfiguration(String groupName, String key);          // null if no active RS profile
public <T> T getRSProfileConfiguration(String groupName, String key, Type clazz);
public <T> void setRSProfileConfiguration(String groupName, String key, T value); // lazily creates profile; no-op if logged out
public void unsetRSProfileConfiguration(String groupName, String key);
// stored as groupName + "." + rsProfileKey + "." + key; profile matched by
// findRSProfile(getRSProfiles(), client.getAccountHash(), RuneScapeProfileType.getCurrent(client), displayName, create)
// posts net.runelite.client.events.RuneScapeProfileChanged on change

// --- gradle (runelite/example-plugin build.gradle) ---
repositories { maven { url = 'https://repo.runelite.net' }; mavenCentral() }
compileOnly group: 'net.runelite', name: 'client', version: 'latest.release'

## Risks
- gameval classes (InventoryID, InterfaceID, ItemID, VarbitID in net.runelite.api.gameval) are auto-generated from Jagex names and constants there differ from the deprecated equivalents (e.g. INV vs INVENTORY, BANKMAIN vs WidgetID.BANK_GROUP_ID); values could shift with game updates, so always compare via the constant, never hardcode 93/95/12.
- The claim that the bank ItemContainer stays stale-but-readable after closing (and is null before first open) is standard RuneLite behavior corroborated by the Bank Memory README and the existence of snapshot-based designs, but there is no explicit javadoc on Client.getItemContainer stating it â€” do a quick runtime sanity check.
- Deposit boxes and bank chests: an ordinary deposit box does NOT open the bank interface; whether the BANK container updates from deposit-box use was not verified â€” the ItemContainerChanged(BANK) snapshot pattern may miss deposits made via deposit box until the next real bank open.
- ItemManager.getItemComposition / canonicalize must run on the client thread (ItemContainerChanged handlers already are); calling from a Swing panel requires ClientThread.invoke/invokeLater.
- Bank Memory checked-in code still uses the deprecated enum style (InventoryID.BANK.getId()) â€” copy its architecture, not its imports; new code should use gameval ints (plugin-hub CI may flag deprecated API usage over time).
- RSProfile config: getRSProfileConfiguration returns null and setRSProfileConfiguration silently no-ops while logged out, so load the snapshot after login / on RuneScapeProfileChanged, and remember profiles are split per world type (a Leagues login is a different RS profile than the same account on a standard world â€” usually desirable).
- RuneLite config (including RSProfile keys and Bank Memory-style JSON blobs) is synced to the user's RuneLite account profile storage; very large bank JSON in config works (Bank Memory does it) but keep serialization compact (id,qty CSV) to avoid bloating profiles.
- ItemContainerChanged fires for many containers (shops, GE collection, raid storage, seed vault=626...); always guard on containerId before processing.
- WORN_ITEMS mapping inside canonicalize maps some worn/degraded variants to base items â€” for a materials checklist this is usually what you want, but be aware counts of charged/degraded gear collapse to the base id.

## Sources
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-api/src/main/java/net/runelite/api/gameval/InventoryID.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-api/src/main/java/net/runelite/api/InventoryID.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-api/src/main/java/net/runelite/api/ItemContainer.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-api/src/main/java/net/runelite/api/events/ItemContainerChanged.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-api/src/main/java/net/runelite/api/events/WidgetClosed.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-api/src/main/java/net/runelite/api/Client.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-api/src/main/java/com/jagex/oldscape/pub/OAuthApi.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-api/src/main/java/net/runelite/api/gameval/InterfaceID.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-api/src/main/java/net/runelite/api/ItemComposition.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-client/src/main/java/net/runelite/client/game/ItemManager.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-client/src/main/java/net/runelite/client/config/ConfigManager.java
- https://raw.githubusercontent.com/runelite/runelite/master/runelite-client/src/main/java/net/runelite/client/plugins/bank/BankPlugin.java
- https://raw.githubusercontent.com/runelite/plugin-hub/master/plugins/bank-memory
- https://github.com/Lazyfaith/runelite-bank-memory-plugin/tree/3174826dadc0a2207fe1ef60ce6a7f6385b5750b/src/main/java/com/bankmemory
- https://raw.githubusercontent.com/Lazyfaith/runelite-bank-memory-plugin/3174826dadc0a2207fe1ef60ce6a7f6385b5750b/src/main/java/com/bankmemory/BankMemoryPlugin.java
- https://raw.githubusercontent.com/Lazyfaith/runelite-bank-memory-plugin/3174826dadc0a2207fe1ef60ce6a7f6385b5750b/src/main/java/com/bankmemory/data/BankSave.java
- https://raw.githubusercontent.com/Lazyfaith/runelite-bank-memory-plugin/3174826dadc0a2207fe1ef60ce6a7f6385b5750b/src/main/java/com/bankmemory/data/PluginDataStore.java
- https://raw.githubusercontent.com/Lazyfaith/runelite-bank-memory-plugin/3174826dadc0a2207fe1ef60ce6a7f6385b5750b/src/main/java/com/bankmemory/data/ConfigReaderWriter.java
- https://raw.githubusercontent.com/Lazyfaith/runelite-bank-memory-plugin/3174826dadc0a2207fe1ef60ce6a7f6385b5750b/src/main/java/com/bankmemory/data/ItemDataParser.java
- https://raw.githubusercontent.com/Lazyfaith/runelite-bank-memory-plugin/3174826dadc0a2207fe1ef60ce6a7f6385b5750b/README.md
- https://raw.githubusercontent.com/runelite/example-plugin/master/build.gradle
