# Research: right-click "Add to Material Checklist" injection

Verified against runelite/runelite master + decompiled clientscripts, 2026-09-05.

## Menu API (current)
- Use `client.getMenu().createMenuEntry(-1)` (index -1 = end of array = TOP of
  rendered menu). `Client#createMenuEntry` is deprecated in favor of `Menu`.
- Fluent `MenuEntry` builder: `.setOption(...).setTarget(...).setType(MenuAction.RUNELITE)
  .setItemId(...).setParam0(widgetChildIndex).setParam1(packedComponentId)
  .onClick(consumer)`. With `MenuAction.RUNELITE` + `onClick`, RuneLite invokes
  the consumer and nothing is sent to the server.
- All relevant events (`MenuOpened`, `MenuEntryAdded`, `WidgetLoaded`,
  `ScriptPostFired`) run on the client thread; widget access must too.
- `MenuEntryAdded` fires per-entry per-frame on hover (keep cheap);
  `MenuOpened` fires once when the right-click menu opens — entries created
  there persist for that menu.

## Skill guides — TWO interfaces (both live since 2026-03-04 update)
- Legacy: `InterfaceID.SKILL_GUIDE = 214`. Icons: `InterfaceID.SkillGuide.ICONS`
  (214:32) dynamic children, one per entry, `getItemId()` set; texts on
  `SkillGuide.INFO` (214:31) at 2i (level) / 2i+1 (description).
- Modern: `InterfaceID.SKILL_GUIDE_V2 = 860`. All rows are dynamic children of
  `InterfaceID.SkillGuideV2.LIST` (860:21), 4 children per row:
  [rect, levelText, itemGraphic, descText]; item id on the graphic child.
  Rebuild clientscript id 1903 (`skill_guide_v2_rebuild`).
- GOTCHA: most skills' guide entries have NO right-click ops (only Magic /
  Construction / Sailing get one), so `MenuEntryAdded` alone can't identify
  the hovered row for e.g. Smithing. Use `MenuOpened` + mouse-bounds hit-test
  over the row/icon widgets (precedent: core InventoryTagsPlugin.onMenuOpened,
  core OverlayRenderer hover injection).
- Filter `getItemId() == -1` (sprite-override rows) and
  `ItemID.BLANKOBJECT` (6512, dummy rows). Some rows are non-item unlocks that
  still carry a representative item id.
- Target color tag: `JagexColors.MENU_TARGET_TAG`.

## Fallback surfaces
- Bank items: `onMenuEntryAdded`, gate `event.getActionParam1() ==
  InterfaceID.Bankmain.ITEMS`, option "Examine"; `event.getItemId()`;
  `itemManager.canonicalize()` for placeholders. (BankTagsPlugin:402-438.)
- Inventory items: gate on `InterfaceID.Inventory.ITEMS` (group 149) +
  option "Examine" (identifier 10), or InventoryTags `onMenuOpened` pattern.

## Name/id mapping
- Widget → item: `Widget.getItemId()` directly (both guides set it via
  cc_setobject). Item → name: `itemManager.getItemComposition(id).getName()`.
- Do NOT use `ItemManager.search()` as primary mapping (tradeables only).

## Risk
- gameval `InterfaceID` constants are generated from Jagex names and the
  4-children-per-row layout is a Jagex clientscript implementation detail —
  keep hit-test defensive (bounds + itemId != -1 checks).

## Reference implementations (provenance)
- WikiPlugin.java:462-506 (menu entry on stats/CA widgets)
- FriendNotesPlugin.java:226-263
- BankTagsPlugin.java:402-438 (bank items)
- InventoryTagsPlugin.java:163-236 (MenuOpened + submenus)
- OverlayRenderer.java:186-227 (injection where no vanilla ops exist)
- quest-helper QuestMenuHandler.java:323-334 (hub example)
