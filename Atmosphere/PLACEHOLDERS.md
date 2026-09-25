# Guest Atmosphere — placeholder assets

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
