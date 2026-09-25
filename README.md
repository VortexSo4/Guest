# Guest

Minecraft **26.1.2** / NeoForge **26.1.2.109** mod set. One repository, one Gradle multiproject, one jar per module.

| Module | Mod id | Role |
|---|---|---|
| `Core` | `guest_core` | Shared contracts: calendar (`GuestTime`, 8-day week = moon cycle, 128-day year), deterministic hashing, weather value + provider (`GuestWeather`), wildlife pressure view (`GuestWildlife`), route traffic event, sparse world history (`GuestHistory`), debug channels, `/guest` command root, `GuestGizmos`. No content. |
| `Atmosphere` | `guest_atmosphere` | Deterministic regional weather model (seed + time + region + biome + season), drives vanilla weather, fog/particles/sounds, blizzard slowdown, reversible weather traces (snow, mud, ice, sand, dry ground, scorch) with catch-up. |
| `Wilds` | `guest_wilds` | Path wear from all movement (TRMT idea) and aggregate route traffic, undead lairs with aggregate populations/competition/traces, day cycle & sheltering, herds & pastures, seasonal variants, fish shoals, endermen near places of power. |
| `Settlements` | `guest_settlements` | Aggregate village simulation with cheap long-absence catch-up, bells & lunar rites, visible internal trade, helpers, gossip-based memory across generations, caravans & roads, seasonal illager patrols, fallen villages. |
| `Architects` | `guest_architects` | Architect entity, living/declining ancient cities (closed-form population), roles & glyph communication, sculk containment/spread, Warden melody, interference escalation, assistance, infected variants, memory niches. |
| `Hands` | `guest_hands` | Pickaxe grappling, physical crafting table / furnaces / anvil / enchanting table / brewing stand (vanilla logic, display entities), slow sleep transition. |

## Build
```
./gradlew build          # compile + tests (JUnit) for all modules
./gradlew runClient      # all mods in dev
./gradlew benchmark      # JMH (or -Pbenchmark=<class>)
./gradlew spotlessApply  # google-java-format
```
Note: the Gradle wrapper fails if the checkout path contains non-ASCII characters.

## Docs
- `PLACEHOLDER_ASSETS.md` — every placeholder asset (also per module).
- `<Module>/DEBUG.md` — debug channels (`/guest debug <channel>`) and commands (`/guest <addon> ...`); `Core/DEBUG.md` for `/guest time add`, `/guest calendar`, `/guest weather`.
- Config: every gameplay change can be toggled/tuned in `<modid>-server.toml` (Atmosphere also has a client config); config screens are available in the Mods menu. All text is localized (`en_us`, `ru_ru`).
