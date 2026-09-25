# Guest Atmosphere — debug tools

## Debug channel `atmosphere`
Toggle with `/guest debug atmosphere` (singleplayer; the renderer reads a snapshot taken on the
server thread every second around the first overworld player).

Shows:
- region cell borders (cyan lines at camera height) and the inner/outer edges of the blend band
  (dim cyan) where neighbouring cells fade into each other; the grid drifts east with the fronts;
- a label over the centre of the 3x3 nearby cells: cell index, climate class, weather type
  (+ aurora), intensity, wind and effective temperature (vanilla biome scale, snow below 0.15);
- "Weather here" in front of the camera for the player's own position;
- every weather trace within 32 blocks as a coloured box (snow white, mud brown, ice light blue,
  sand beige, dry ground tan, scorch dark grey) and, per chunk within 2 chunks, trace counts by
  kind and ticks since the chunk was last brought up to date.

## Commands (`/guest atmosphere ...`, permission level 2)
- `here` — weather, intensity, wind, temperature, aurora, climate class and region cell here.
- `forecast [segments]` — model weather at this position for the next N segments (default 8).
  The model is deterministic, so this is exactly what will happen (unless forced).
- `force <type> [intensity] [minutes]` — override weather within half a cell around you
  (default intensity 1, 10 minutes). In memory only; not saved. Snow types also force frost.
- `unforce` — remove all overrides.
- `traces [radius]` — count traces by kind in loaded chunks within `radius` chunks (default 1).
- `catchup [radius]` — immediately replay the full catch-up window (`catchUpMaxDays`) for loaded
  chunks around you. Combine with `/guest time add <days>` to check what a long absence leaves.

Core commands that help: `/guest weather` (the value other addons read), `/guest calendar`,
`/guest time add <days>` (chunks near players become stale and catch up automatically, a few per
tick).

## Tests / benchmark
- `./gradlew :Atmosphere:test` — `WeatherModelTest`: determinism, biome/season constraints,
  rain frequency vs vanilla, border smoothness.
- `./gradlew :Atmosphere:jmh -Pbenchmark=com.vortexso.guest_atmosphere.benchmark.WeatherModelBenchmark`
  — cost of one model query (~100–135 ns on the dev machine, without the biome lookup).
