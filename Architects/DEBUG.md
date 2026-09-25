# Guest Architects — debug tools

## Debug channel `architects`

Toggle with `/guest debug architects` (singleplayer: the renderer reads the integrated server's state).
The server builds a snapshot once per second only while the channel is on.

Per city (within 256 blocks of the camera):
- teal box: whole ancient city (structure bounding box);
- cyan box: ritual room (the building holding most vanilla sculk patches) — sculk inside it (+3 blocks) is allowed;
- purple box: city center / portal frame piece (deepslate work, wool floor, memorial candles);
- green points: sculk-patch origins (catalyst sites) used for containment and abandoned spread;
- label: city id, living/abandoned, long-term state, derived population, assistance delay (days),
  sculk (applied abandoned spread radius, stray sculk queued for maintainers, unrepaired damage),
  recognition used, and per player `name: interference points / assistance`.

Per Architect: `#index role: activity`, its city id, and an arrow to the current task target
(or the Warden it is playing the melody for).

## Commands (`/guest architects ...`, permission level 2)

All act on the nearest discovered city in the current dimension.

| Command | Effect |
|---|---|
| `list` | All discovered cities: id, center, distance, current population |
| `info` | Derived profile (living, state, base/now population), assistance delay, abandonment day, applied spread radius, memorial niches, recognition flag, pending damage, known players |
| `living <true\|false>` / `living auto` | Override whether the city is inhabited (auto = deterministic seed result) |
| `state <stable\|fluctuating\|declining\|auto>` | Override the long-term state |
| `materialize` | Re-run activation (containment/spread, niches, Architect population) on the next observed second |
| `reset` | Clear overrides, player relations, assistance delay and the recognition flag (world changes stay) |

Useful with Core: `/guest time add <days>` jumps the clock; the next activation (leave and come back,
or `materialize`) shows the city after that time — decline, deaths as candles, abandoned sculk spread.
`/summon guest_architects:architect` creates a city-less Architect (idles; no drops, immune except `/kill`).

## Persistent data

SavedData `guest_architects:cities` (per dimension): per city only overrides,
assistance delay, recognition flag, player relations, unrepaired damage, applied spread radius and
placed niches. Population, state and deaths are always derived. Forced removals and recognition moments
are also written to Core's `GuestHistory` (`guest_architects:forced_removal`, `guest_architects:recognition`).
