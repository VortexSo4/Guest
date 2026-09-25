# Guest Architects — placeholder assets

All assets already use their final paths; replacing them needs no code change.

| Asset | Path | Placeholder | Final asset |
|---|---|---|---|
| Architect model | `client/ArchitectsClient#ARCHITECT_LAYER` (`guest_architects:architect#main`) | Vanilla `HumanoidModel` mesh (64x64 player UV), stretched in `ArchitectRenderer#scale` to ~2.1 blocks, narrow shoulders | Dedicated elongated thin humanoid model with long robe; keep the layer location, swap the `LayerDefinition` (and the `scale` override if the model already has final proportions) |
| Architect texture | `src/main/resources/assets/guest_architects/textures/entity/architect.png` | Generated 64x64: pale ashy skin, dark hood/robe, faint cyan embroidery lines | Final skin: pale/ashy, long dark clothing with faint luminous sculk-thread embroidery, tools carried |
| Architect eye glow | `src/main/resources/assets/guest_architects/textures/entity/architect_eyes.png` | Four cyan eye pixels, rendered emissive (`RenderTypes.eyes`) | Faint cyan/turquoise sculk-like eye light (plus embroidery glow if wanted) |
| Melody sound | `sounds.json` → `guest_architects:architect.melody` | Event reference to `minecraft:block.note_block.flute` (pitch set per note in code) | Dedicated Architect melody instrument note (one note, code varies pitch) |
| Portal sound | `sounds.json` → `guest_architects:architect.portal` | Event reference to `minecraft:block.portal.travel` at 0.4 volume | Sound of the Architect-created removal portal |

Young Architects reuse the adult model/texture scaled to 0.6; a separate young texture/model is optional.
