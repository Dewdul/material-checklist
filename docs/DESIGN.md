# Material Checklist — Design (settled by research, 2026-09-05)

All API facts verified against runelite/runelite master. See docs/research/*.

## Decisions

1. **Recipe data: bundled wiki-generated JSON** (`recipes.json` in resources,
   loaded via `getResourceAsStream` + injected Gson). Generated offline by
   `tools/generate_recipes.py` from the OSRS Wiki Bucket API
   (`bucket('recipe')` joined with `infobox_item`, `infobox_construction`,
   `infobox_ship_part`). ~4,800 recipes, all production skills incl. Sailing.
   We additionally keep **skill + level** per recipe (incumbent discards it).
   Attribution: OSRS Wiki CC BY-NC-SA 3.0 in README (established hub practice).
2. **Products are name-keyed.** `Recipe{name, productId, skill, level,
   ingredients[{itemId, quantity, same[]}], makes}`; `productId == 0` means
   buildable scenery (icon falls back to first ingredient; wiki link falls back
   to name search). Persistence stores names, never product ids.
3. **Search**: bundled recipe-name index, ranked substring (exact > prefix >
   contains, shorter first), EDT-synchronous. NOT `itemManager.search()`
   (tradeables-only; misses ~half the product space).
4. **Owned counts** = live inventory (`gameval InventoryID.INV`) + bank
   snapshot. Bank snapshot taken on `ItemContainerChanged` (containerId ==
   `InventoryID.BANK`), canonicalized (`itemManager.canonicalize`), filtered
   (id<=0, qty==0, BANK_FILLER), persisted as **sorted compact CSV** via
   `configManager.setRSProfileConfiguration("materialchecklist","bankSnapshot",csv)`
   — the core-LootTracker/DWMS pattern; reviewers prefer config over files.
   Reloaded on login + `RuneScapeProfileChanged`.
5. **Checklist state**: goal tree serialized as JSON (injected Gson) in plain
   config key (`materialchecklist.goals`) — account-independent like the
   incumbents. Drill-down = adding a child goal for an intermediate (that
   material's recipe inputs replace it in the aggregate), collapse = revert.
6. **Views**: `PluginPanel(false)` + `MaterialTabGroup` with two tabs:
   - **Materials**: aggregated raw materials, `have / need` colored
     green (inventory covers) / white (inv+bank covers) / yellow (shortfall
     craftable from stock) / red (short); progress bar; optional GE cost of
     missing materials (differentiator; `itemManager.getItemPrice`).
   - **Goods**: one collapsible section per goal; click a material row that
     has a recipe to expand it into a child goal (recursive drill-down).
   Icons `itemManager.getImage(...).addTo(label)`; empty state
   `PluginErrorPanel`; search `IconTextField` + `JSpinner` quantity.
7. **Right-click add (user requirement)**: `MenuOpened` + mouse hit-test over
   both skill guides — legacy `InterfaceID.SkillGuide.ICONS/INFO` (group 214)
   and modern `InterfaceID.SkillGuideV2.LIST` (group 860, 4 children/row) —
   then `client.getMenu().createMenuEntry(-1)` with `MenuAction.RUNELITE` +
   `onClick` (client-side only, nothing sent to server). Entry only appears
   when the hovered item has a recipe. Filter itemId -1 / BLANKOBJECT (6512).
   NO menu injection into POH furniture/Sailing build menus (hub rule) —
   those get passive shift-click capture via `MenuOptionClicked` instead.
   Optional config: add entry on inventory/bank items (MenuEntryAdded on
   Examine, default off).
8. **Threading**: counts + composition/name lookups on the client thread
   (memoized id→name via getMembersName()), immutable snapshot handed to EDT
   via `SwingUtilities.invokeLater`. Search/goal rendering is EDT-only.
9. **Naming**: package `com.materialchecklist`, plugin "Material Checklist",
   config group `materialchecklist`, repo project name `material-checklist`.
10. **Toolchain**: example-plugin scaffold (Gradle 8.10 wrapper, Java 11
    target, `net.runelite:client:latest.release`, Lombok 1.18.30); build
    locally with the Microsoft JDK 21 (Gradle 8.10 can't run on JDK 25).
    Tests validate recipes.json (count, unique names, spot-check known ids,
    positive quantities, no self-referential products).

## Known duplication (disclosure)
- `recipe-checklist` (KludensVogter/Recipes_Checklist, hub Aug 2026) is
  feature-for-feature this plugin; `recipe-tracker`, `required-materials`
  overlap heavily. Differentiators here: GE cost-to-complete, level display,
  skill-guide right-click menu entry (incumbent is shift-click only).
  Decision on hub submission deferred to the user.
