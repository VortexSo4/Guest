# Guest Settlements — debug tools

## Debug channel

`/guest debug settlements` toggles world-space gizmos (singleplayer only: the renderer reads the
integrated server's live state; nothing is simulated for display).

- Village structure box: cyan = observed (fully loaded, vanilla villagers live for real),
  orange = simulated (aggregate), red = fallen. Point instead of a box = neighbour known only
  from a structure search.
- Label above the village: population/children, beds (vanilla HOME POIs) and farmland,
  food reserve with production/consumption per day, expected births/day and losses/night,
  hostile pressure (`GuestWildlife`), next lunar rite or mourning tonight, infected count if
  fallen, and profession counts.
- Gold box: the bell (MEETING POI) used for rites and the storm bell.
- Green boxes + label: scanned farmland per village farm piece (`*partly unloaded` when chunks
  were missing during the scan).
- Yellow lines: roads (persisted nearest-neighbour edges).
- Magenta boxes on roads: caravans in transit with progress %, "(lost)" if it will not arrive.
- Text above villagers: the role `VillageLife` currently gives them (rite, bell ringer, mourning,
  heading to the bell, trading, helping, minding children).

## Commands (`/guest settlements ...`, permission level 2)

| Command | What it does |
| --- | --- |
| `info` | Aggregate state and rates of the nearest village. |
| `simulate <days>` | Runs `<days>` of absence on the nearest village at once (the same catch-up used on load), re-materializes it if loaded, prints the cost in ms. |
| `caravans` | Lists caravans currently on the road (from → to, progress, lost). |
| `history` | Settlement memories (GuestHistory records of this addon) within 96 blocks, with age and witness count. |
| `patrols` | Current patrol clock multiplier (season × new moon; 0 in a snowstorm). |

Related Core tools: `/guest time add <days>` (then load a village to watch it catch up),
`/guest calendar`, `/guest weather`.

## Where state lives

- `VillageWorldData` (SavedData `guest_settlements:villages`): per village id, center and
  `VillageState` (population by profession, beds, farmland, food, fallen/infected, last death
  day, last simulated day) + roads. Everything else (farm regions, bells, loaded chunks, trade
  scenes, roles) is rebuilt from the world.
- `GuestHistory` (Core): memories `guest_settlements:villager_killed|villager_saved|
  home_destroyed|villager_cured|villager_infected|village_fallen|illager_killed`.
- Entity tags: `guest_settlements.infected` (zombie villagers of a fallen village),
  `guest_settlements.carrying` (item in hand from a trade scene), `guest_settlements.curer:<uuid>`
  (who started a cure).

## Benchmark

`./gradlew :Settlements:jmh -Pbenchmark=com.vortexso.guest_settlements.benchmark.VillageSimulatorBenchmark`
— catch-up cost for 1 day / 1 year / 100 years at several population sizes.
