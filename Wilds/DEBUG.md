# Guest Wilds — debug tools

All tools read the live simulation state; nothing here runs a separate simulation.
Gizmos only work in singleplayer (the renderer reads the integrated server's state) and only
in the Overworld (Wilds simulates the Overworld only).

## Debug channels (`/guest debug <channel>`)

| Channel | Shows |
|---|---|
| `wilds_lairs` | Purple box (9×5×9) around each known lair within 128 blocks. Label: cave openness (0 = crawlway, 1 = open chamber) and hostile pressure at the lair (radius 64, 1.00 = vanilla). One coloured line per species present: aggregate population, how many are materialized ("seen"), how many are displaced (emigrants looking for a new cave). "Spider riders established" once spiders and skeletons have coexisted long enough for jockeys. |
| `wilds_paths` | A point + wear value above every worn column within 24 blocks. Colour = applied stage: green 0 (worn but unchanged), yellow 1 trampled, orange 2 coarse dirt, red 3 path. Columns that only hold aggregate wear in not-yet-reconciled chunks are skipped. |
| `wilds_herds` | Yellow box over each herd's current 64×64 pasture cell, arrow from the previous pasture (last migration), label: species, aggregate animals, materialized animals, pasture vegetation (1.00 = untouched). |
| `wilds_fish` | Label over each tracked shoal (32×32 water cells): water kind, fish / capacity, salmon share of the catch. Full unwatched shoals are forgotten (they are the default), so only fished or watched shoals appear. |

## Commands (`/guest wilds ...`, permission level 2)

| Command | Effect |
|---|---|
| `lair` | Nearest known lair within 256 blocks: position, openness, rider status, per-species population / seen / displaced. Catches the node up to now first. |
| `herd` | Nearest herd within 256 blocks: species, animals, seen, pasture centre, vegetation. |
| `shoal` | Shoal of the water you stand in: kind, fish, capacity, salmon share, visible fish. |
| `pressure [radius]` | Value `GuestWildlife.hostilePressure` returns here (what Settlements reads). Default radius 64. |
| `wear <amount>` | Adds wear to the column under your feet through the normal path (stages apply immediately). `wear 100` makes a path block. |
| `route <from> <to> <trips>` | Posts a real Core `RouteTrafficEvent` (traveler `guest_wilds:debug`), exactly what Settlements sends for unobserved travel. Wear lands on loaded columns immediately and on unloaded ones when their chunks load. |
| `simulate <days>` | Jumps the overworld clock like `/guest time add` and then catches up every lair, herd and worn column at once. Use it to see what weeks/years of absence do (paths regrow, lairs fill or empty, herds migrate). |

## Useful checks

- Paths: `wear 100` on grass → dirt path; `simulate 30` → back to grass (plants restored when they had been removed by Wilds).
- Route: `route ~ ~ ~ ~80 ~ ~ 60` then walk along it with `wilds_paths` on; the meander is deterministic per route.
- Lairs: go underground near a lair box, check `lair`; kill a member and see its population drop by one; `simulate 8` to watch regrowth.
- Riders: need a lair with openness around 0.55–0.7 where spiders and skeletons both stay ≥ 2 for 16 days.

## Tuning

Aggregate rates are in `WildsParameters.DEFAULT` (each with its vanilla basis in comments);
player-facing switches are in the SERVER config `guest_wilds-server.toml`.
