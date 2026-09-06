#!/usr/bin/env python3
"""Generate recipes.json for the Material Checklist RuneLite plugin.

Pulls structured recipe data from the OSRS Wiki's Bucket API and joins
material/product names to item ids. Run before each release; the output is
bundled into the plugin jar and the plugin itself never touches the network.

Data (c) OSRS Wiki contributors, CC BY-NC-SA 3.0
(https://oldschool.runescape.wiki/w/RuneScape:Copyrights). Attribution is
carried in the plugin README.

Stdlib only. Usage:  python tools/generate_recipes.py
"""
import json
import re
import sys
import time
import urllib.parse
import urllib.request
from datetime import date
from pathlib import Path

API = "https://oldschool.runescape.wiki/api.php"
USER_AGENT = "MaterialChecklist-RuneLite-plugin-generator/1.0 (recipe data build script)"
OUT = Path(__file__).resolve().parent.parent / "src/main/resources/com/materialchecklist/recipes.json"

WIKI_LINK = re.compile(r"\[\[(?:[^\]|]*\|)?([^\]|]*)\]\]")
# requires a space before "(" so "Prayer potion(4)" keeps its dose suffix
TRAILING_QUALIFIER = re.compile(r"\s+\([^()]*\)$")
QUANTITY = re.compile(r"^(\d+(?:\.\d+)?)")


def query(q):
    url = API + "?" + urllib.parse.urlencode({"action": "bucket", "format": "json", "query": q})
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                data = json.load(resp)
            if "bucket" not in data:
                raise RuntimeError("unexpected bucket response: %s" % list(data)[:5])
            return data["bucket"]
        except Exception as e:  # noqa: BLE001 - retry then re-raise
            if attempt == 3:
                raise
            print("  retry after error: %s" % e, file=sys.stderr)
            time.sleep(3 * (attempt + 1))


def fetch_all(bucket, fields):
    rows, offset = [], 0
    while True:
        sel = ",".join("'%s'" % f for f in fields)
        page = query("bucket('%s').select(%s).limit(2000).offset(%d).run()" % (bucket, sel, offset))
        rows.extend(page)
        print("  %s: +%d rows (total %d)" % (bucket, len(page), len(rows)))
        if len(page) < 2000:
            return rows
        offset += 2000


def clean(name):
    """Strip wiki link markup; return (name, anchor)."""
    if not isinstance(name, str):
        return "", ""
    name = WIKI_LINK.sub(r"\1", name).strip()
    anchor = ""
    if "#" in name:
        name, anchor = name.split("#", 1)
    return name.strip(), anchor.strip()


def parse_quantity(text, low_end=True):
    """'1', '2-3', '0.5', 'Varies' -> int or None. Ranges take the low end."""
    if isinstance(text, (int, float)):
        return max(1, round(float(text)))
    if not isinstance(text, str):
        return None
    m = QUANTITY.match(text.strip())
    if not m:
        return None
    return max(1, round(float(m.group(1))))


def first_int(value):
    """Bucket item_id may be an int, a numeric string, a list, or junk like
    'interface8283'. Return the first parseable int or None."""
    if isinstance(value, list):
        for v in value:
            got = first_int(v)
            if got is not None:
                return got
        return None
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return None


def build_name_index():
    index = {}
    for bucket, name_field in (
        ("infobox_item", "item_name"),
        ("infobox_construction", "page_name"),
        ("infobox_ship_part", "page_name"),
    ):
        for row in fetch_all(bucket, [name_field, "item_id"]):
            name, _ = clean(row.get(name_field, ""))
            item_id = first_int(row.get("item_id"))
            if name and item_id is not None:
                # first row wins: canonical/older pages come first in bucket order
                index.setdefault(name.lower(), item_id)
    return index


def build_watered_aliases(index):
    """Watered saplings/seedlings: item 'X (w)' counts as item 'X'."""
    aliases = {}
    for name, item_id in index.items():
        if name.endswith(" (w)"):
            base = index.get(name[:-4].strip())
            if base is not None:
                aliases[base] = item_id
    return aliases


def resolve(index, name):
    """Look up a cleaned name, retrying with trailing ' (qualifier)' stripped."""
    for _ in range(3):
        got = index.get(name.lower())
        if got is not None:
            return got
        stripped = TRAILING_QUALIFIER.sub("", name)
        if stripped == name:
            return None
        name = stripped
    return index.get(name.lower())


def main():
    print("Fetching recipe bucket...")
    raw = fetch_all("recipe", ["production_json"])
    print("Building name -> item id index...")
    index = build_name_index()
    watered = build_watered_aliases(index)
    print("  %d names indexed, %d watered aliases" % (len(index), len(watered)))

    skipped = {"empty": 0, "self": 0, "unknown_material": 0, "bad_quantity": 0, "dupe": 0}
    unresolved_products = []
    recipes = []
    seen_signatures = set()

    for row in raw:
        pj = row.get("production_json")
        if isinstance(pj, str):
            try:
                pj = json.loads(pj)
            except ValueError:
                skipped["empty"] += 1
                continue
        if not isinstance(pj, dict):
            skipped["empty"] += 1
            continue

        output = pj.get("output") or {}
        materials = pj.get("materials") or []
        out_name, out_anchor = clean(output.get("name", ""))
        if not out_name or not materials:
            skipped["empty"] += 1
            continue

        makes = parse_quantity(output.get("quantity", "1")) or 1
        product_id = resolve(index, out_name) or 0
        if not product_id:
            # potions/jewellery pages often name the product without its
            # dose/charge suffix — try the common ones before concluding
            # this is buildable scenery with no item behind it
            for suffix in (" (4)", " (3)", " (8)", " (10)", " (2)", " (1)"):
                got = index.get((out_name + suffix).lower())
                if got:
                    product_id = got
                    break
        if not product_id:
            unresolved_products.append(out_name)

        ingredients = []
        ok = True
        for mat in materials:
            mat_name, _ = clean(mat.get("name", ""))
            mat_id = resolve(index, mat_name)
            if mat_id is None:
                skipped["unknown_material"] += 1
                ok = False
                break
            qty = parse_quantity(mat.get("quantity", "1"))
            if qty is None:
                skipped["bad_quantity"] += 1
                ok = False
                break
            ing = {"itemId": mat_id, "quantity": qty}
            # never alias to the recipe's own product (watering a seedling
            # would otherwise count the product as its own ingredient)
            if mat_id in watered and watered[mat_id] != product_id:
                ing["same"] = [watered[mat_id]]
            ingredients.append(ing)
        if not ok or not ingredients:
            continue

        # merge duplicate ingredient ids (some recipes list an item twice)
        merged = {}
        for ing in ingredients:
            if ing["itemId"] in merged:
                merged[ing["itemId"]]["quantity"] += ing["quantity"]
            else:
                merged[ing["itemId"]] = ing
        ingredients = sorted(merged.values(), key=lambda i: i["itemId"])

        # a product listed as its own input (charging, refills) cannot be planned
        if product_id and any(i["itemId"] == product_id for i in ingredients):
            skipped["self"] += 1
            continue

        # primary skill requirement (first listed with a numeric level)
        skill, level = "", 0
        for sk in pj.get("skills") or []:
            lvl = parse_quantity(sk.get("level", ""))
            if isinstance(sk.get("name"), str) and lvl:
                skill, level = sk["name"].strip(), lvl
                break

        subtxt, _ = clean(output.get("subtxt", ""))
        facilities, _ = clean(pj.get("facilities", "") if isinstance(pj.get("facilities"), str) else "")
        variant = subtxt or out_anchor or ""

        # productId=0 scenery carries no identity in its product id — include
        # the name/variant so distinct buildables with identical materials
        # (e.g. STASH tiers) are not collapsed as duplicates
        identity = product_id if product_id else "%s|%s|%s" % (out_name.lower(), variant.lower(), facilities.lower())
        signature = (identity, level, makes, tuple((i["itemId"], i["quantity"]) for i in ingredients))
        if signature in seen_signatures:
            skipped["dupe"] += 1
            continue
        seen_signatures.add(signature)

        recipes.append({
            "name": out_name,
            "variant": variant,
            "facilities": facilities,
            "productId": product_id,
            "skill": skill,
            "level": level,
            "makes": makes,
            "ingredients": ingredients,
        })

    # Disambiguate colliding names: prefer variant, then facilities, then (n).
    by_name = {}
    for r in recipes:
        by_name.setdefault(r["name"].lower(), []).append(r)
    final = []
    for group in by_name.values():
        if len(group) > 1:
            for r in group:
                if r["variant"]:
                    r["name"] = "%s (%s)" % (r["name"], r["variant"])
                elif r["facilities"]:
                    r["name"] = "%s (%s)" % (r["name"], r["facilities"])
        final.extend(group)
    counts = {}
    for r in final:
        key = r["name"].lower()
        counts[key] = counts.get(key, 0) + 1
        if counts[key] > 1:
            r["name"] = "%s (%d)" % (r["name"], counts[key])
        del r["variant"]
        del r["facilities"]
        if not r["skill"]:
            del r["skill"]
            del r["level"]
        if r["makes"] == 1:
            del r["makes"]

    final.sort(key=lambda r: r["name"].lower())
    payload = {
        "generated": date.today().isoformat(),
        "source": "OSRS Wiki (oldschool.runescape.wiki), CC BY-NC-SA 3.0",
        "recipes": final,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, separators=(",", ":"))
    print("Wrote %d recipes to %s" % (len(final), OUT))
    print("Skipped: %s" % skipped)
    no_product = sum(1 for r in final if not r["productId"])
    print("Recipes with productId=0 (buildable scenery): %d" % no_product)
    if unresolved_products:
        print("Product names that did not resolve to an item id (%d):" % len(unresolved_products))
        for name in sorted(set(unresolved_products)):
            print("  - %s" % name)


if __name__ == "__main__":
    main()
