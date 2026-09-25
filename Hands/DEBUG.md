# Guest Hands — debug

Toggle with `/guest debug hands` (GuestDebug channel `hands`). Server-side values are read from the
integrated server, so the server parts only draw in singleplayer.

| Gizmo | Meaning |
|---|---|
| Green box + point | Block the local player's pickaxe currently grips, point on the gripped face |
| Yellow arrow | Gaze (drives climb / descend / sideways) |
| Cyan arrow at feet | Current velocity ×10 |
| "Carried: N blocks" | Server-measured distance the pickaxe has carried in this grip (durability basis) |
| Cyan cell boxes | The 9 grid cells on every crafting table within 8 blocks |
| Magenta lines + `station/role` | Every Hands display entity within 16 blocks, linked to its anchor block |
| "Night pass: …" | Running slow-sleep pass in this dimension: ticks elapsed, clock span, state |

No extra commands: `/guest time add` (Core) and vanilla `/kill @e[tag=guest_hands]` cover testing.
Removing displays with `/kill` deletes items stored on crafting tables / anvils / enchanting tables.
