# Guest — placeholder assets (all modules)

Every placeholder already uses its final id/path; replacing it needs no code change.
Per-module details: `<Module>/PLACEHOLDERS.md`.

## Architects

All assets already use their final paths; replacing them needs no code change.

| Asset | Path | Placeholder | Final asset |
|---|---|---|---|
| Architect model | `client/ArchitectsClient#ARCHITECT_LAYER` (`guest_architects:architect#main`) | Vanilla `HumanoidModel` mesh (64x64 player UV), stretched in `ArchitectRenderer#scale` to ~2.1 blocks, narrow shoulders | Dedicated elongated thin humanoid model with long robe; keep the layer location, swap the `LayerDefinition` (and the `scale` override if the model already has final proportions) |
| Architect texture | `src/main/resources/assets/guest_architects/textures/entity/architect.png` | Generated 64x64: pale ashy skin, dark hood/robe, faint cyan embroidery lines | Final skin: pale/ashy, long dark clothing with faint luminous sculk-thread embroidery, tools carried |
| Architect eye glow | `src/main/resources/assets/guest_architects/textures/entity/architect_eyes.png` | Four cyan eye pixels, rendered emissive (`RenderTypes.eyes`) | Faint cyan/turquoise sculk-like eye light (plus embroidery glow if wanted) |
| Melody sound | `sounds.json` → `guest_architects:architect.melody` | Event reference to `minecraft:block.note_block.flute` (pitch set per note in code) | Dedicated Architect melody instrument note (one note, code varies pitch) |
| Portal sound | `sounds.json` → `guest_architects:architect.portal` | Event reference to `minecraft:block.portal.travel` at 0.4 volume | Sound of the Architect-created removal portal |

Young Architects reuse the adult model/texture scaled to 0.6; a separate young texture/model is optional.

## Atmosphere

All assets already use their final ids; replacing a placeholder only needs a new file and/or a
`sounds.json` edit, no code change.

| Final id / path | Current placeholder | Final asset |
|---|---|---|
| sound event `guest_atmosphere:weather.wind` (`assets/guest_atmosphere/sounds.json`) | vanilla `item/elytra/elytra_loop`, pitch 0.6 | seamless loop of steady wind in open terrain, 20–40 s, stereo-neutral |
| sound event `guest_atmosphere:weather.blizzard` | vanilla `item/elytra/elytra_loop`, pitch 0.8 | seamless loop of a roaring snow storm (gusts, hissing snow) |
| sound event `guest_atmosphere:weather.sandstorm` | vanilla `item/elytra/elytra_loop`, pitch 0.5 | seamless loop of wind carrying sand (dry hiss, low rumble) |
| aurora visual (`client/ClientWeatherEffects#onRenderLevel`) | translucent green gizmo cuboids ("curtains") drawn ~110 blocks north, 45–80 blocks above the camera | a real sky effect: animated aurora ribbons rendered in the sky pass (custom sky renderer or shader), fading with sky exposure |
| sandstorm particles | vanilla block particles of sand / red sand | optional dedicated dust particle (fine grains, horizontal streaks) |
| lightning scorch | vanilla coarse dirt | acceptable as final; a darker "charred" look would need a new block and is intentionally not added (no new content) |

No textures, models or structures are added by this module.

## Settlements

None. Settlements adds no textures, models, sounds or structures: everything it shows uses
vanilla content (villagers, bells and the vanilla bell sound, held vanilla items, happy-villager
particles, zombie villagers, pillager patrols).

## Wilds

None. Wilds adds no textures, models, sounds or structures: every visible result uses vanilla
content (vanilla mobs and their variants, and vanilla blocks as traces).

Traces that a dedicated asset could replace later (only if the project decides to add content):

| Trace | Currently | Where |
|---|---|---|
| Trampled path stages | `coarse_dirt`, `dirt_path` | `path/PathWear.java` |
| Zombie lair bedding | `rooted_dirt` + `brown_mushroom` | `lair/LairSpecies.java` |
| Skeleton lair remains | `bone_block` + `skeleton_skull` | `lair/LairSpecies.java` |
| Spider cocoons / webs | `cobweb` | `lair/LairSpecies.java` |
| Creeper scorch / crater | `blackstone` + removed floor block | `lair/LairSpecies.java` |

## Hands

None. Hands adds no textures, models, sounds or structures: everything is shown with vanilla
display entities, vanilla sounds (block hit/step sounds, item frame, page turn, bottle) and the
vanilla enchanting (`minecraft:alt`) font.

## Core

None (Core adds no content).
