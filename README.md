# Auto-Pitch40

A [ZenithProxy](https://github.com/rfresh2/ZenithProxy) plugin that flies the bot with Pitch40 elytra travel while you're offline.

The bot swings between +40 pitch (diving to gain speed) and -40 pitch (climbing to trade speed for height), turning around at a lower and an upper Y. It holds that height band for as long as it has elytra durability, and can top itself up with rockets when it starts to sink.

## Features

- **Pitch40 flight** between two configurable heights, with an optional fixed heading
- **Rockets only where they pay off**:
  - `keep`: a rocket only when a climb tops out too far under the upper height, or as an emergency when sinking well under the lower one
  - `boost`: as `keep`, plus a rocket every few climbs for speed
- **Chest swap**: every few ticks it swaps a chestplate on and the elytra straight back, then restarts the glide, so the elytra takes almost no durability damage
- **Elytra replacement**: swaps in the best spare elytra mid-flight when the worn one runs low
- **Auto deploy**: reopens the elytra if the glide drops out mid-air
- **Auto log**: disconnects if a flight sinks too far under the lower height, and stops AutoReconnect logging back in

## Installing

Requires the `java` ZenithProxy release channel (plugins aren't supported on `linux`).

1. Build it with `./gradlew build` (or download the jar from the latest Actions run)
2. Put the jar from `build/libs` in the `plugins` folder next to the ZenithProxy launcher
3. Restart ZenithProxy

## Using it

1. Start gliding at or above the upper height. If you're carrying rockets, you can start anywhere
2. Run `pitch40 on`
3. Disconnect. The bot only flies while no player is controlling the proxy

It pauses while ZenithProxy's own pathfinder is active, stops when it lands, and disconnects if it sinks too far under the lower height (see `autoLog`).

## Commands

Type these in the ZenithProxy terminal as shown, in game with `!` or `/` in front, or in Discord with `.` in front.

| Command | Default | What it does |
|---|---|---|
| `pitch40 on/off` | off | Turns it on or off |
| `pitch40 bounds <lower> <upper>` | 360 420 | Climbs below `lower`, dives above `upper` |
| `pitch40 rotationSpeed <degrees>` | 4 | Pitch change per tick |
| `pitch40 yaw <degrees>/off` | off | Locks a heading, or keeps the current one |
| `pitch40 rockets <off/keep/boost>` | keep | Rocket mode |
| `pitch40 rockets heightTolerance <blocks>` | 10 | How far under the upper height a climb may top out before a rocket is spent. Higher saves rockets but flies lower |
| `pitch40 rockets climbsPerRocket <count>` | 1 | Boost only: a rocket every Nth climb |
| `pitch40 elytra autoDeploy on/off` | on | Reopens the elytra while falling |
| `pitch40 elytra replace on/off` | on | Swaps in a spare elytra when the worn one runs low |
| `pitch40 elytra replace durability <n>` | 10 | Durability left at which it's replaced (minimum 2) |
| `pitch40 elytra warning <n>` | 20 | Warns at this durability when there's no spare left |
| `pitch40 chestSwap on/off` | off | Chest swap for near-zero durability loss |
| `pitch40 chestSwap interval <ticks>` | 4 | Ticks between swaps (1-19) |
| `pitch40 chestSwap slot <0-8>` | 7 | Hotbar slot a chestplate is moved into if none is in the hotbar |
| `pitch40 autoLog on/off` | on | Disconnects if the bot sinks too far under the lower height mid-flight |
| `pitch40 autoLog below <blocks>` | 40 | How far under the lower height it can sink first. With bounds 360-420 that's Y 320. The emergency rockets fire at 10 and 30 under, so they get a chance first |
| `pitch40 autoLog cancelReconnect on/off` | on | Stops AutoReconnect logging straight back in afterwards |
| `pitch40 priority <n>` | 7500 | Input priority. Beats AntiAFK; KillAura and AutoEat still win |

Run `pitch40` on its own to see the current settings and what the bot is doing.

Settings are saved in `plugins/config/pitch40.json`. Stop ZenithProxy before editing the file by hand. The rocket hotbar slot (`rockets.hotbarSlot`, default 8) can only be changed there, and only takes effect after a restart.

## What to carry

- An elytra in the chest slot, plus spares if you want replacement
- Firework rockets (plain ones, with no explosion effects) anywhere in the inventory, unless rockets are `off`
- A chestplate anywhere in the inventory, if chest swap is on

## Code layout

```
src/main/java/io/github/dukkkkkkkk/pitch40/
├── Pitch40Plugin.java       plugin entry point
├── Pitch40Config.java       settings (saved as JSON)
├── command/
│   └── Pitch40Command.java  the pitch40 command
├── module/
│   ├── Pitch40Module.java   flight loop: pitch steering and rocket decisions
│   ├── GlideCycle.java      tracks climbs and decides when a rocket is due
│   ├── RocketTracker.java   waits for a fired rocket to show up before allowing another
│   ├── ElytraManager.java   durability checks and spare elytra replacement
│   ├── ChestSwap.java       the chest swap packets
│   └── AutoLog.java         disconnects if a flight sinks too low
└── util/
    ├── Items.java           item checks and inventory slot lookups
    └── Notice.java          show-once warnings
```

## Credits

The plugin structure comes from the [ZenithProxy example plugin](https://github.com/rfresh2/ZenithProxyExamplePlugin).
tehpicix for helping with the chestfly
