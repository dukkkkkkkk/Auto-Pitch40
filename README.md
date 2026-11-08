# Auto-Pitch40 by Duk

A [ZenithProxy](https://github.com/rfresh2/ZenithProxy) plugin that flies the bot with Pitch40 elytra travel while you're offline.

The bot swings between +40 pitch (diving to gain speed) and -40 pitch (climbing to trade speed for height), turning around at a lower and an upper Y. It holds that height band for as long as it has elytra durability, and can top itself up with rockets when it starts to sink.

## Features

- **Pitch40 flight** between two configurable heights, with an optional fixed heading
- **No ceiling mode**: drop the upper height and let it climb as high as the rockets take it
- **Rockets only where they pay off**:
  - `keep`: a rocket on the next climb whenever a climb tops out more than `heightTolerance` short of the upper height (at most one per `gap`), so climbs keep reaching the top of the band. More than `heightTolerance` under the lower height, a rocket on each climb until it's back in. Halfway down to the auto log height (20 under by default) it stops cycling, points up and rockets without waiting, so it doesn't sink far enough to auto log
  - `boost`: as `keep`, plus a rocket every few climbs for speed
- **Chest swap**: every few ticks it swaps a chestplate on and the elytra straight back, then restarts the glide, so the elytra takes almost no durability damage
- **Elytra replacement**: swaps in a spare elytra mid-flight when the worn one runs low, using up part-worn ones first and saving the fullest for last
- **Auto regear**: when it's down to its second-to-last good elytra, it lands, places a shulker of XP bottles from the inventory, swaps spare rockets for XP, throws the XP to mend all the elytras, mines the shulker back up and takes off again
- **Last elytra**: disconnects as soon as it's down to its last elytra, so that one stays full for the trip home
- **Auto launch**: after the proxy connects, it takes off by itself and rockets up to the upper height, then starts flying
- **Auto deploy**: reopens the elytra if the glide drops out mid-air
- **Auto log**: disconnects if a flight sinks too far under the lower height, and stops AutoReconnect logging back in
- **Discord status**: the Discord bot's status shows how far it's travelled this session, e.g. `2b2t [01:05:12] | 12,345 blocks`, updated once a minute
- **Daily coords**: posts the bot's position, dimension and distance travelled to Discord (and the terminal) every 24 hours
- **Ask for coords**: whisper the bot `eta` or `current`, or just say it in public chat, and it whispers back its position, speed and flight state. Only whitelisted players get an answer, and it's always a whisper

## Installing

Requires the `java` ZenithProxy release channel (plugins aren't supported on `linux`).

1. Download the jar from the [latest release](https://github.com/dukkkkkkkk/Auto-Pitch40/releases/latest) (or build it with `./gradlew build` and take it from `build/libs`)
2. Put the jar in the `plugins` folder next to the ZenithProxy launcher
3. Restart ZenithProxy

## Using it

1. Stand somewhere with open sky above, wearing an elytra and carrying rockets (or already be gliding)
2. Run `pitch40 on`
3. Disconnect from the proxy

The bot only moves while no player is logged into the proxy. While you're connected, your client is in control and Pitch40 waits; it takes over the moment you disconnect. When it does, it takes off and rockets up to the upper height if it's standing or gliding low, then starts flying. The same happens when the proxy connects to the server.

`pitch40 on` and `pitch40` both say what it's doing or waiting for, e.g. "you're logged into the proxy" or "no elytra in the chest slot".

It pauses while ZenithProxy's own pathfinder is active, stops when it lands, and disconnects if it sinks too far under the lower height (see `autoLog`).

## Commands

Type these in the ZenithProxy terminal as shown, in game with `!` or `/` in front, or in Discord with `.` in front.

| Command | Default | What it does |
|---|---|---|
| `pitch40 on/off` | off | Turns it on or off |
| `pitch40 bounds <lower> <upper>` | 360 420 | Climbs below `lower`, dives above `upper` |
| `pitch40 lower <y>` | 360 | Sets just the lower height |
| `pitch40 ceiling on/off` | on | Off removes the upper height (see below) |
| `pitch40 climbGain <blocks>` | 20 | With the ceiling off: each climb aims this far above the best peak so far, and a climb that falls short gets a rocket next time, so it keeps climbing. 0 just holds the best height |
| `pitch40 diveDepth <blocks>` | 60 | With the ceiling off: how far under each peak it dives before climbing again |
| `pitch40 stayInBounds on/off` | on | Learns how far it overshoots each turn and turns that much earlier, so it really stays between the bounds (60 block dives with 360-420). Off, it rises about 24 blocks past the upper bound before diving (about 90 block dives) but averages about 10% faster |
| `pitch40 rotationSpeed <degrees>` | 4 | Pitch change per tick |
| `pitch40 yaw <degrees>/off` | off | Locks a heading, or keeps the current one |
| `pitch40 quad set <xz>` | | Locks the heading to a quadrant or axis: X sign then Z sign. Corners `++` `+-` `-+` `--`, axes `+0` `-0` `0+` `0-`. E.g. `++` flies toward +X +Z, `0-` straight toward -Z |
| `pitch40 quad off` | | Unlocks the heading |
| `pitch40 rockets <off/keep/boost>` | keep | Rocket mode |
| `pitch40 rockets heightTolerance <blocks>` | 10 | How far under the upper height a climb may top out before a rocket is spent. Higher saves rockets but flies lower |
| `pitch40 rockets climbsPerRocket <count>` | 1 | Boost only: a rocket every Nth climb |
| `pitch40 rockets gap <seconds>` | 30 | Least time between rockets, except launches and stalls |
| `pitch40 rockets lowWarning <count>` | 32 | Warns in Discord when this many rockets or fewer are left, and again when they run out. Out of rockets, it keeps doing Pitch40 and glides down slowly instead of pointing up and stalling |
| `pitch40 elytra autoDeploy on/off` | on | Reopens the elytra while falling |
| `pitch40 elytra replace on/off` | on | Swaps in a spare elytra when the worn one runs low |
| `pitch40 elytra replace durability <n>` | 10 | Durability left at which it's replaced (minimum 2) |
| `pitch40 elytras` | | Lists your elytras by durability: the worn one, then spares grouped like `9x full`, `4x 300`, `1x 160`. Spares too low to swap to are marked |
| `pitch40 elytra minSpare <n>` | 10 | A spare needs at least this much durability before the bot will swap to it (and always more than the replace durability) |
| `pitch40 elytra wornFirst on/off` | on | Swaps in the most-used spare first (least durability left, still above `minSpare`), so part-worn elytras get finished off and the fullest are saved for last. Off: fullest first |
| `pitch40 elytra last on/off` | on | Disconnects as soon as it swaps onto its last elytra, so you keep one full elytra to fly home with. Needs at least one spare to start with. AutoReconnect is cancelled too unless `autoLog cancelReconnect` is off |
| `pitch40 elytra warning <n>` | 20 | Warns at this durability when there's no spare left |
| `pitch40 chestSwap on/off` | off | Chest swap for near-zero durability loss |
| `pitch40 chestSwap dukway` | | Turns chest swap on in dukway mode: chestplate on for `interval` ticks, then the elytra for 1 tick with a glide start, over and over. The elytra goes back on before rockets, elytra replacement, landing, disconnecting or a player taking over |
| `pitch40 chestSwap quick` | quick | Turns chest swap on in the default mode: elytra stays on, with a same-tick chestplate swap and glide restart every `interval` ticks |
| `pitch40 chestSwap interval <ticks>` | 4 | Ticks between swaps (1-19) |
| `pitch40 chestSwap pauseForRockets on/off` | on | Stops swapping while a rocket is boosting so the elytra stays on for the whole boost, then goes back to swapping |
| `pitch40 chestSwap slot <0-8>` | 7 | Hotbar slot a chestplate is moved into if none is in the hotbar |
| `pitch40 regear on/off` | off | When the bot gets down to its second-to-last good elytra (one spare left), it lands with the pathfinder (which finds a safe spot), places the XP shulker next to itself, puts spare rockets in and takes XP out, throws the XP straight down to mend every elytra (they need Mending; ZenithProxy's AutoMend is switched on for it), mines the shulker back up and launches. Needs a shulker with XP bottles in the inventory, and no other shulker of the same colour |
| `pitch40 regear now` | | Regears straight away |
| `pitch40 regear keepRockets <count>` | 128 | Rockets to keep when putting spares into the shulker |
| `pitch40 launch on/off` | on | When the bot takes control (proxy connects, you disconnect, or you turn Pitch40 on) while standing or gliding low, it takes off and rockets up to the upper height |
| `pitch40 launch now` | | Launches straight away (or as soon as you disconnect from the proxy) |
| `pitch40 launch height <y>` | upper bound | Height to climb to when taking off or taking over. Set it above the lower bound to give the bot room to sink before it needs rockets (each ~35 blocks is about one rocket). `launch height upper` goes back to the upper bound |
| `pitch40 launch pitch <degrees>` | 60 | How steeply it climbs while rocketing up (10-90 degrees above level) |
| `pitch40 launch delay <seconds>` | 5 | Wait after connecting before taking off, so the world has loaded |
| `pitch40 autoLog on/off` | on | Disconnects if the bot glides or falls too far under the lower height, and when it's falling within 16 blocks of the bottom of the world (the void), whatever the bounds are. Checked every tick the bot is in control, whether it's flying, waiting to start, launching or pathfinding. It can't act while you're logged into the proxy, since your client is in control then |
| `pitch40 autoLog below <blocks>` | 40 | How far under the lower height it can sink first. With bounds 360-420 that's Y 320. The emergency rockets fire at 10 and 30 under, so they get a chance first |
| `pitch40 autoLog cancelReconnect on/off` | on | Stops AutoReconnect logging straight back in afterwards |
| `pitch40 autoLog illegal on/off` | on | Disconnects by sending packets the server always kicks for (attacking yourself, an invalid position), so the server ends the session itself. A normal disconnect follows a second later as a fallback. Used by auto log, last elytra and `logout` |
| `pitch40 logout` | | Disconnects right now (the illegal way if that's on), putting the elytra back first and blocking AutoReconnect |
| `pitch40 coords on/off` | on | Posts the bot's coords to Discord and the terminal on an interval. Works whether or not Pitch40 is on |
| `pitch40 coords interval <hours>` | 24 | Hours between reports |
| `pitch40 coords now` | | Sends a report straight away and restarts the timer |
| `pitch40 discordStatus on/off` | on | The Discord bot's status shows distance travelled since it last connected, like `2b2t [01:05:12] \| 12,345 blocks`, updated once a minute. Takes over the status from ZenithProxy (its `managePresence` is switched off while this is on, and back on when you turn this off) |
| `pitch40 whisper on/off` | on | Whispers the position back when a whitelisted player whispers a keyword |
| `pitch40 whisper add <keyword>` | eta, current | Adds a keyword. It can be anywhere in the message as a whole word, in any case, so client prefixes and suffixes like `> current \| Hackware [a7a25b9f]` still work. `eta` doesn't match inside `beta` |
| `pitch40 whisper remove <keyword>` | | Removes a keyword |
| `pitch40 whisper publicChat on/off` | on | Also answers a keyword said in public chat. The reply is still a whisper |
| `pitch40 whisper antiSpam on/off` | on | Adds a random tag like `[s2o9f]` to each reply so the server's spam filter doesn't drop repeats |
| `pitch40 priority <n>` | 7500 | Input priority. Beats AntiAFK; KillAura and AutoEat still win |

Run `pitch40` on its own to see the current settings and what the bot is doing, including how many rockets went on what (launch, holding height, climb gain, boost, stall).

Launches are where most rockets go: about 10 to climb from Y 80 to 420, and about 37 to 1500. A high upper bound costs a lot every time the bot has to launch.

### No ceiling

`pitch40 ceiling off` removes the upper height. Each climb runs until it tops out, then the bot dives `diveDepth` blocks under that peak and climbs again. Rockets are measured against the best peak so far instead of the upper height:

- `keep` aims each climb `climbGain` (default 20) blocks above the best peak so far, and fires a rocket on the next climb when one falls short. In a simulation that's about one rocket every other climb and about 34 blocks gained per climb. Set `climbGain` to 0 to just hold the best height
- `boost` also adds a rocket every climb (or every `climbsPerRocket`)

Pitch40 on its own (rockets off) gains a few blocks per climb.

The lower height still works as a floor: it always climbs there, and the emergency rockets and auto log still go off it. If you're flying far above it, raise it with `pitch40 lower <y>` so auto log triggers before a long fall.

The coords report is timed from the last one sent, and that time is saved, so restarts don't reset it. If the proxy was offline when one was due, it goes out as soon as it's back in game. If ZenithProxy's `discord reportCoords` setting is off, the coordinates only go to the terminal.

Auto launch won't take off if there's no elytra on, no rockets, a block within 4 above the bot's head, or the upper height is above the nether roof while the bot is under it. It gives up and warns if the elytra won't open after 3 jumps, it runs out of rockets, or it hasn't reached the height after 90 seconds. It uses rockets even if the rocket mode is `off`.

Replies only go to players on ZenithProxy's whitelist (`whitelist add <name>`), matched by UUID, since they can already log into the proxy and see where it is. Anyone else is ignored (whispers from them are logged to the terminal). Replies are always whispers, even when the keyword was said in public chat, so the coords never go to public chat. Each player can get one reply every 5 seconds.

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
│   ├── AutoLog.java         disconnects if a flight sinks too low
│   ├── Launcher.java        takes off and rockets up after connecting
│   └── CoordsModule.java    daily coords report and whisper replies
└── util/
    ├── Items.java           item checks and inventory slot lookups
    └── Notice.java          show-once warnings
```

## Credits

The plugin structure comes from the [ZenithProxy example plugin](https://github.com/rfresh2/ZenithProxyExamplePlugin).

tehpicix for helping with the chestfly
