# Material Checklist

A RuneLite plugin for planning what you want to craft — across every
production skill — and tracking the raw materials you still need.

## Features

- **Add finished goods** from a search of ~4,900 recipes (all production
  skills, including Construction and Sailing buildables), or straight from
  the game: right-click an entry in any **skill guide** and choose
  *Add to Material Checklist*. Shift-clicking entries in the POH furniture
  and ship customisation menus adds them too, and an optional setting adds
  the entry to craftable inventory/bank items.
- **Materials view** — every raw material you still need, aggregated across
  your whole checklist, shown as `have / need` and counting both your
  **inventory and bank**. The bank is snapshotted whenever you open it and
  remembered per account between sessions.
- **Goods view** — your planned products with their material breakdowns.
  Click a material that can itself be crafted (molten glass, unstrung bows,
  planks...) to explore its own recipe, recursively. The Materials view
  always tracks the **direct** materials of the goods you added; when you
  plan to craft an intermediate yourself, right-click it and *Add as its
  own goal* — its materials then join the list too.
- **GE cost to finish** — see roughly what buying your missing materials
  would cost (optional).
- Color coding: green = enough in inventory, white = enough counting bank,
  yellow = short but craftable, red = short.
- Finished products you already own reduce what's left to make (optional).

## Colors

| Color | Meaning |
| --- | --- |
| Green | Inventory alone covers it |
| White | Inventory + bank covers it |
| Yellow | Short, but the material can be crafted from its own recipe |
| Red | Short |

## Notes

- Bank quantities come from a snapshot taken whenever your bank is open —
  open your bank once after installing for banked materials to count.
- Recipe variants matter: e.g. *Steel bar (Normal furnace)* needs 2 coal,
  *(Blast Furnace)* needs 1. Search for the variant you plan to use; the
  in-game add picks the simplest method by default.

## Recipe data

Recipe data is generated at build time from the [OSRS Wiki](https://oldschool.runescape.wiki)
by `tools/generate_recipes.py` and bundled into the plugin — the plugin
itself never makes a network request. Wiki content is licensed
[CC BY-NC-SA 3.0](https://oldschool.runescape.wiki/w/RuneScape:Copyrights);
thanks to the OSRS Wiki contributors.

## Development

Requires JDK 11+ (the Gradle build targets Java 11). To run a development
client with the plugin loaded:

```
gradlew run
```

To regenerate the recipe dataset (Python 3, stdlib only):

```
python tools/generate_recipes.py
```
