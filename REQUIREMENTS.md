# Material Checklist — RuneLite Plugin Requirements

## Concept
A RuneLite side-panel plugin where the user builds a list of items they want to
craft/make across production skills (Smithing, Crafting, Herblore, Fletching,
Cooking, Construction, ...). The plugin computes the aggregate raw materials
required and tracks progress against what the player actually owns.

## Core requirements (from user)
1. **Add finished goods** to a checklist, with quantities.
2. **Raw Materials view** — aggregated totals of all raw materials needed
   across every planned item, showing *have vs. need*, where "have" counts
   **inventory + bank** contents.
3. **Finished Goods view** — all planned products.
4. **Drill-down navigation** — clicking a finished good shows its required
   materials; clicking an intermediate material that is itself craftable
   (e.g. molten glass, unstrung bow) expands into *its* recipe, recursively.
5. **Right-click add from skill guides** — while browsing the in-game skill
   guide dialogs (Skills tab → click a skill), right-clicking a listed product
   offers "Add to Material Checklist".
6. **Genre common practice** — adopt whatever similar Plugin Hub plugins
   (supply trackers, crafting calculators, checklists) treat as table stakes
   (researched: GE prices, progress bars, color coding, search, persistence,
   etc. — final list driven by research findings).

## Environment
- Windows 11, Temurin JDK 25 + Microsoft JDK 21 installed, git 2.52.
- Project root: `C:\Users\scott\RuneScape` (plugin repo lives here).

## Status
- 2026-09-05: Research phase running (RuneLite API current state, similar
  plugins, recipe data sourcing, bank snapshot patterns, menu entry injection).
