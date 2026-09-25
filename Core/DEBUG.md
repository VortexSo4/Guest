# Guest Core — debug tools

All commands require permission level 2 (operator / cheats enabled).

| Command | Effect |
|---|---|
| `/guest time add <days>` | Jumps the overworld clock forward without ticking. Every Guest system catches up from elapsed time, so this is the main way to check what months/years of absence do. |
| `/guest calendar` | Year, season, week of season, day of the 8-day week and moon phase. |
| `/guest weather` | Weather at your position as reported by `GuestWeather` (Atmosphere's model when installed, vanilla otherwise). |
| `/guest debug <channel>` | Toggles a world-space debug channel. Tab-completion lists the channels registered by installed addons. |

Debug rendering reads the integrated server's live state, so it works in singleplayer (or LAN host) only.
Addon-specific channels and commands are documented in each addon's `DEBUG.md`.

Helpers for addon authors: `GuestDebug.register/isEnabled`, `GuestGizmos` (boxes, lines, arrows, points, billboard text; `Component` overload resolves translations on the client).
