package io.github.dukkkkkkkk.pitch40.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.Proxy;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.discord.Embed;
import io.github.dukkkkkkkk.pitch40.Pitch40Config.RocketMode;
import io.github.dukkkkkkkk.pitch40.module.CoordsModule;
import io.github.dukkkkkkkk.pitch40.module.Pitch40Module;
import io.github.dukkkkkkkk.pitch40.util.Quadrant;

import java.time.Duration;
import java.util.Locale;

import static com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg;
import static com.mojang.brigadier.arguments.DoubleArgumentType.getDouble;
import static com.mojang.brigadier.arguments.FloatArgumentType.floatArg;
import static com.mojang.brigadier.arguments.FloatArgumentType.getFloat;
import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static com.zenith.Globals.MODULE;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;
import static io.github.dukkkkkkkk.pitch40.Pitch40Plugin.CONFIG;

public class Pitch40Command extends Command {
    private static final double MIN_Y = -128;
    private static final double MAX_Y = 100_000;

    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("pitch40")
            .category(CommandCategory.MODULE)
            .description("""
                Pitch40 elytra travel for the bot.

                Dives at +40 pitch until the lower bound, then climbs at -40 until the upper bound.
                Start it while gliding at or above the upper bound (or anywhere if carrying rockets).

                With the ceiling off there is no upper bound: each climb runs until it tops out,
                then it dives diveDepth blocks under that peak. With boost rockets it keeps gaining height.

                Rockets:
                  off - free glide only
                  keep - a rocket only when a climb tops out more than heightTolerance under the upper bound
                  boost - keep, plus a rocket every Nth climb

                Chest swap keeps elytra durability loss near zero by swapping a hotbar chestplate on and off.

                Auto log disconnects if a flight sinks too far under the lower bound.

                Launch: after the proxy connects, the bot takes off and rockets up to the upper bound,
                then starts Pitch40. Needs an elytra on, rockets, and open sky above.

                Coords posts the bot's position to Discord and the terminal every intervalHours (default 24).
                Whisper the bot "eta" or "current" (or say it in public chat) and it whispers its position back.
                Whitelisted players only.

                Only runs while no player is controlling the proxy, and pauses while the pathfinder is active.
                """)
            .usageLines(
                "on/off",
                "bounds <lower> <upper>",
                "lower <y>",
                "ceiling on/off",
                "diveDepth <blocks>",
                "rotationSpeed <degrees per tick>",
                "yaw <degrees>/off",
                "quad set <++/+-/-+/--/+0/-0/0+/0->",
                "quad off",
                "rockets <off/keep/boost>",
                "rockets heightTolerance <blocks>",
                "rockets climbsPerRocket <count>",
                "elytra autoDeploy on/off",
                "elytra replace on/off",
                "elytra replace durability <durability>",
                "elytra warning <durability>",
                "chestSwap on/off",
                "chestSwap interval <ticks>",
                "chestSwap slot <hotbar slot 0-8>",
                "launch on/off",
                "launch now",
                "launch pitch <degrees up>",
                "launch delay <seconds>",
                "autoLog on/off",
                "autoLog below <blocks under the lower bound>",
                "autoLog cancelReconnect on/off",
                "coords on/off",
                "coords interval <hours>",
                "coords now",
                "whisper on/off",
                "whisper add <keyword>",
                "whisper remove <keyword>",
                "whisper publicChat on/off",
                "whisper antiSpam on/off",
                "priority <priority>"
            )
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("pitch40")
            .then(argument("toggle", toggle()).executes(c -> {
                CONFIG.enabled = getToggle(c, "toggle");
                var module = MODULE.get(Pitch40Module.class);
                module.syncEnabledFromConfig();
                var embed = c.getSource().getEmbed()
                    .title("Pitch40 " + toggleStrCaps(CONFIG.enabled));
                // say what happens next, so "nothing happened" always has an answer
                if (CONFIG.enabled) embed.description(module.status());
            }))
            .then(boundsCommand())
            .then(lowerCommand())
            .then(ceilingCommand())
            .then(diveDepthCommand())
            .then(literal("rotationSpeed").then(argument("degrees", floatArg(0.5f, 20f)).executes(c -> {
                CONFIG.corridor.rotationSpeed = getFloat(c, "degrees");
                c.getSource().getEmbed()
                    .title("Rotation Speed Set");
            })))
            .then(literal("yaw")
                .then(literal("off").executes(c -> {
                    CONFIG.corridor.lockYaw = false;
                    CONFIG.corridor.quad = "";
                    c.getSource().getEmbed()
                        .title("Yaw Lock Off");
                }))
                .then(argument("degrees", floatArg(-180f, 180f)).executes(c -> {
                    CONFIG.corridor.lockYaw = true;
                    CONFIG.corridor.yaw = getFloat(c, "degrees");
                    CONFIG.corridor.quad = "";
                    c.getSource().getEmbed()
                        .title("Yaw Locked");
                })))
            .then(quadCommands())
            .then(rocketCommands())
            .then(elytraCommands())
            .then(chestSwapCommands())
            .then(launchCommands())
            .then(autoLogCommands())
            .then(coordsCommands())
            .then(whisperCommands())
            .then(literal("priority").then(argument("priority", integer()).executes(c -> {
                CONFIG.priority = getInteger(c, "priority");
                c.getSource().getEmbed()
                    .title("Priority Set");
            })));
    }

    private LiteralArgumentBuilder<CommandContext> boundsCommand() {
        return literal("bounds").then(argument("lower", doubleArg(MIN_Y, MAX_Y)).then(argument("upper", doubleArg(MIN_Y, MAX_Y)).executes(c -> {
            double lower = getDouble(c, "lower");
            double upper = getDouble(c, "upper");
            if (upper <= lower) {
                c.getSource().getEmbed()
                    .title("Error")
                    .description("The upper bound must be above the lower bound");
                return ERROR;
            }
            CONFIG.corridor.lowerBound = lower;
            CONFIG.corridor.upperBound = upper;
            c.getSource().getEmbed()
                .title("Bounds Set");
            return OK;
        })));
    }

    private LiteralArgumentBuilder<CommandContext> lowerCommand() {
        return literal("lower").then(argument("y", doubleArg(MIN_Y, MAX_Y)).executes(c -> {
            double lower = getDouble(c, "y");
            if (CONFIG.corridor.ceiling && lower >= CONFIG.corridor.upperBound) {
                c.getSource().getEmbed()
                    .title("Error")
                    .description("The lower bound must be under the upper bound (" + CONFIG.corridor.upperBound
                        + "). Turn the ceiling off to fly without one");
                return ERROR;
            }
            CONFIG.corridor.lowerBound = lower;
            c.getSource().getEmbed()
                .title("Lower Bound Set");
            return OK;
        }));
    }

    private LiteralArgumentBuilder<CommandContext> ceilingCommand() {
        return literal("ceiling").then(argument("toggle", toggle()).executes(c -> {
            CONFIG.corridor.ceiling = getToggle(c, "toggle");
            c.getSource().getEmbed()
                .title("Ceiling " + toggleStrCaps(CONFIG.corridor.ceiling))
                .description(CONFIG.corridor.ceiling
                    ? "Climbs stop at Y " + CONFIG.corridor.upperBound
                    : "No height limit: dives " + CONFIG.corridor.diveDepth + " blocks under each peak");
        }));
    }

    private LiteralArgumentBuilder<CommandContext> diveDepthCommand() {
        return literal("diveDepth").then(argument("blocks", doubleArg(10, 1000)).executes(c -> {
            CONFIG.corridor.diveDepth = getDouble(c, "blocks");
            c.getSource().getEmbed()
                .title("Dive Depth Set");
        }));
    }

    /**
     * Locks the heading to a quadrant or axis: X sign then Z sign, e.g. "++", "+0", "0-".
     */
    private LiteralArgumentBuilder<CommandContext> quadCommands() {
        return literal("quad")
            .then(literal("set").then(argument("quad", word()).executes(c -> {
                String quad = Quadrant.parse(getString(c, "quad"));
                if (quad == null) {
                    c.getSource().getEmbed()
                        .title("Error")
                        .description("Use an X sign then a Z sign, each +, - or 0. "
                            + "Corners: ++ +- -+ --. Axes: +0 -0 0+ 0-");
                    return ERROR;
                }
                CONFIG.corridor.lockYaw = true;
                CONFIG.corridor.yaw = Quadrant.yaw(quad);
                CONFIG.corridor.quad = quad;
                c.getSource().getEmbed()
                    .title("Locked to " + quad)
                    .description("Heading " + Quadrant.describe(quad) + ", yaw " + Math.round(CONFIG.corridor.yaw));
                return OK;
            })))
            .then(literal("off").executes(c -> {
                CONFIG.corridor.lockYaw = false;
                CONFIG.corridor.quad = "";
                c.getSource().getEmbed()
                    .title("Quad Lock Off")
                    .description("Keeping the current heading");
            }));
    }

    private LiteralArgumentBuilder<CommandContext> rocketCommands() {
        return literal("rockets")
            .then(argument("mode", enumStrings("off", "keep", "boost")).executes(c -> {
                CONFIG.rockets.mode = RocketMode.valueOf(getString(c, "mode").toUpperCase());
                c.getSource().getEmbed()
                    .title("Rockets Set");
            }))
            .then(literal("heightTolerance").then(argument("blocks", doubleArg(0, 100)).executes(c -> {
                CONFIG.rockets.heightTolerance = getDouble(c, "blocks");
                c.getSource().getEmbed()
                    .title("Height Tolerance Set");
            })))
            .then(literal("climbsPerRocket").then(argument("count", integer(1, 10)).executes(c -> {
                CONFIG.rockets.climbsPerRocket = getInteger(c, "count");
                c.getSource().getEmbed()
                    .title("Climbs Per Rocket Set");
            })));
    }

    private LiteralArgumentBuilder<CommandContext> elytraCommands() {
        return literal("elytra")
            .then(literal("autoDeploy").then(argument("toggle", toggle()).executes(c -> {
                CONFIG.elytra.autoDeploy = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Auto Deploy " + toggleStrCaps(CONFIG.elytra.autoDeploy));
            })))
            .then(literal("replace")
                .then(argument("toggle", toggle()).executes(c -> {
                    CONFIG.elytra.autoReplace = getToggle(c, "toggle");
                    c.getSource().getEmbed()
                        .title("Elytra Replace " + toggleStrCaps(CONFIG.elytra.autoReplace));
                }))
                .then(literal("durability").then(argument("durability", integer(2, 432)).executes(c -> {
                    CONFIG.elytra.replaceDurability = getInteger(c, "durability");
                    c.getSource().getEmbed()
                        .title("Replace Durability Set");
                }))))
            .then(literal("warning").then(argument("durability", integer(0, 432)).executes(c -> {
                CONFIG.elytra.lowDurabilityWarning = getInteger(c, "durability");
                c.getSource().getEmbed()
                    .title("Durability Warning Set");
            })));
    }

    private LiteralArgumentBuilder<CommandContext> chestSwapCommands() {
        return literal("chestSwap")
            .then(argument("toggle", toggle()).executes(c -> {
                CONFIG.chestSwap.enabled = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Chest Swap " + toggleStrCaps(CONFIG.chestSwap.enabled));
            }))
            .then(literal("interval").then(argument("ticks", integer(1, 19)).executes(c -> {
                CONFIG.chestSwap.intervalTicks = getInteger(c, "ticks");
                c.getSource().getEmbed()
                    .title("Chest Swap Interval Set");
            })))
            .then(literal("slot").then(argument("slot", integer(0, 8)).executes(c -> {
                int slot = getInteger(c, "slot");
                int rocketSlot = MODULE.get(Pitch40Module.class).rocketHotbarSlot();
                if (slot == rocketSlot) {
                    c.getSource().getEmbed()
                        .title("Error")
                        .description("Slot " + rocketSlot + " is the rocket slot, pick another");
                    return ERROR;
                }
                CONFIG.chestSwap.hotbarSlot = slot;
                c.getSource().getEmbed()
                    .title("Chest Swap Slot Set");
                return OK;
            })));
    }

    private LiteralArgumentBuilder<CommandContext> launchCommands() {
        return literal("launch")
            .then(argument("toggle", toggle()).executes(c -> {
                CONFIG.launch.enabled = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Launch " + toggleStrCaps(CONFIG.launch.enabled))
                    .description(CONFIG.launch.enabled
                        ? "After connecting, the bot takes off and rockets up to Y " + (int) CONFIG.corridor.upperBound
                        : "The bot has to be gliding before Pitch40 starts");
            }))
            .then(literal("now").executes(c -> {
                if (!CONFIG.enabled) {
                    c.getSource().getEmbed()
                        .title("Error")
                        .description("Turn Pitch40 on first: `pitch40 on`");
                    return ERROR;
                }
                MODULE.get(Pitch40Module.class).launchNow();
                c.getSource().getEmbed()
                    .title("Launching")
                    .description(Proxy.getInstance().hasActivePlayer()
                        ? "Takes off once you disconnect from the proxy (within 2 minutes)"
                        : "Taking off to Y " + (int) CONFIG.corridor.upperBound);
                return OK;
            }))
            .then(literal("pitch").then(argument("degrees", floatArg(10f, 90f)).executes(c -> {
                CONFIG.launch.pitch = getFloat(c, "degrees");
                c.getSource().getEmbed()
                    .title("Launch Pitch Set");
            })))
            .then(literal("delay").then(argument("seconds", integer(0, 120)).executes(c -> {
                CONFIG.launch.delaySeconds = getInteger(c, "seconds");
                c.getSource().getEmbed()
                    .title("Launch Delay Set");
            })));
    }

    private LiteralArgumentBuilder<CommandContext> autoLogCommands() {
        return literal("autoLog")
            .then(argument("toggle", toggle()).executes(c -> {
                CONFIG.autoLog.enabled = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Auto Log " + toggleStrCaps(CONFIG.autoLog.enabled));
            }))
            .then(literal("below").then(argument("blocks", doubleArg(0, 500)).executes(c -> {
                CONFIG.autoLog.blocksBelowLowerBound = getDouble(c, "blocks");
                c.getSource().getEmbed()
                    .title("Auto Log Height Set")
                    .description("Disconnects under Y " + (int) (CONFIG.corridor.lowerBound - CONFIG.autoLog.blocksBelowLowerBound));
            })))
            .then(literal("cancelReconnect").then(argument("toggle", toggle()).executes(c -> {
                CONFIG.autoLog.cancelReconnect = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Cancel Reconnect " + toggleStrCaps(CONFIG.autoLog.cancelReconnect));
            })));
    }

    private LiteralArgumentBuilder<CommandContext> coordsCommands() {
        return literal("coords")
            .then(argument("toggle", toggle()).executes(c -> {
                CONFIG.coordReport.enabled = getToggle(c, "toggle");
                MODULE.get(CoordsModule.class).syncEnabledFromConfig();
                c.getSource().getEmbed()
                    .title("Coords Report " + toggleStrCaps(CONFIG.coordReport.enabled));
            }))
            .then(literal("interval").then(argument("hours", doubleArg(0.1, 720)).executes(c -> {
                CONFIG.coordReport.intervalHours = getDouble(c, "hours");
                c.getSource().getEmbed()
                    .title("Coords Interval Set")
                    .description("Next report in " + formatDuration(MODULE.get(CoordsModule.class).timeUntilNextReport()));
            })))
            .then(literal("now").executes(c -> {
                if (!Proxy.getInstance().isConnected()) {
                    c.getSource().getEmbed()
                        .title("Error")
                        .description("Not connected to the server");
                    return ERROR;
                }
                MODULE.get(CoordsModule.class).report("Coords");
                c.getSource().getEmbed()
                    .title("Coords Sent");
                return OK;
            }));
    }

    private LiteralArgumentBuilder<CommandContext> whisperCommands() {
        return literal("whisper")
            .then(argument("toggle", toggle()).executes(c -> {
                CONFIG.whisperReply.enabled = getToggle(c, "toggle");
                MODULE.get(CoordsModule.class).syncEnabledFromConfig();
                c.getSource().getEmbed()
                    .title("Whisper Reply " + toggleStrCaps(CONFIG.whisperReply.enabled));
            }))
            .then(literal("add").then(argument("keyword", word()).executes(c -> {
                String keyword = getString(c, "keyword").toLowerCase(Locale.ROOT);
                if (!CONFIG.whisperReply.keywords.contains(keyword)) CONFIG.whisperReply.keywords.add(keyword);
                c.getSource().getEmbed()
                    .title("Keyword Added")
                    .description("Keywords: " + String.join(", ", CONFIG.whisperReply.keywords));
            })))
            .then(literal("publicChat").then(argument("toggle", toggle()).executes(c -> {
                CONFIG.whisperReply.publicChat = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Public Chat Trigger " + toggleStrCaps(CONFIG.whisperReply.publicChat))
                    .description("Replies are always whispered");
            })))
            .then(literal("antiSpam").then(argument("toggle", toggle()).executes(c -> {
                CONFIG.whisperReply.antiSpam = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Anti Spam " + toggleStrCaps(CONFIG.whisperReply.antiSpam));
            })))
            .then(literal("remove").then(argument("keyword", word()).executes(c -> {
                String keyword = getString(c, "keyword").toLowerCase(Locale.ROOT);
                CONFIG.whisperReply.keywords.removeIf(k -> k.equalsIgnoreCase(keyword));
                c.getSource().getEmbed()
                    .title("Keyword Removed")
                    .description(CONFIG.whisperReply.keywords.isEmpty()
                        ? "No keywords left, so nothing will trigger a reply"
                        : "Keywords: " + String.join(", ", CONFIG.whisperReply.keywords));
            })));
    }

    private static String formatDuration(Duration duration) {
        if (!duration.isPositive()) return "under a minute";
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
    }

    @Override
    public void defaultEmbed(Embed embed) {
        var module = MODULE.get(Pitch40Module.class);
        var corridor = CONFIG.corridor;
        var rockets = CONFIG.rockets;
        var elytra = CONFIG.elytra;
        var chestSwap = CONFIG.chestSwap;
        embed
            .primaryColor()
            .addField("Pitch40", toggleStr(CONFIG.enabled))
            .addField("Status", module.status())
            .addField("Bounds", corridor.ceiling
                ? corridor.lowerBound + " - " + corridor.upperBound
                : corridor.lowerBound + " - no ceiling | dives " + corridor.diveDepth + " under each peak")
            .addField("Rotation Speed", corridor.rotationSpeed + " deg/tick")
            .addField("Heading", !corridor.lockYaw ? "current heading"
                : !corridor.quad.isEmpty() ? "quad " + corridor.quad + " " + Quadrant.describe(corridor.quad) + ", yaw " + Math.round(corridor.yaw)
                : "yaw " + corridor.yaw)
            .addField("Rockets", rockets.mode.name().toLowerCase()
                + " | tolerance " + rockets.heightTolerance
                + (rockets.mode == RocketMode.BOOST ? " | every " + rockets.climbsPerRocket + " climbs" : ""))
            .addField("Rockets Carried", module.rocketCount() + " (slot " + module.rocketHotbarSlot() + ")")
            .addField("Auto Deploy", toggleStr(elytra.autoDeploy))
            .addField("Elytra Replace", toggleStr(elytra.autoReplace) + " at " + elytra.replaceDurability
                + " | " + module.spareElytraCount() + " spares")
            .addField("Durability Warning", elytra.lowDurabilityWarning)
            .addField("Chest Swap", toggleStr(chestSwap.enabled) + " | every " + chestSwap.intervalTicks
                + " ticks | slot " + module.chestplateHotbarSlot())
            .addField("Launch", toggleStr(CONFIG.launch.enabled) + " | to Y " + (int) CONFIG.corridor.upperBound
                + " | " + Math.round(CONFIG.launch.pitch) + " deg up | " + CONFIG.launch.delaySeconds + "s after connecting")
            .addField("Auto Log", toggleStr(CONFIG.autoLog.enabled)
                + " | under Y " + (int) (CONFIG.corridor.lowerBound - CONFIG.autoLog.blocksBelowLowerBound)
                + " (" + CONFIG.autoLog.blocksBelowLowerBound + " below the lower bound)"
                + (CONFIG.autoLog.cancelReconnect ? " | no reconnect" : ""))
            .addField("Coords Report", toggleStr(CONFIG.coordReport.enabled) + " | every " + CONFIG.coordReport.intervalHours
                + " hours" + (CONFIG.coordReport.enabled
                    ? " | next in " + formatDuration(MODULE.get(CoordsModule.class).timeUntilNextReport())
                    : ""))
            .addField("Whisper Reply", toggleStr(CONFIG.whisperReply.enabled) + " | keywords: "
                + (CONFIG.whisperReply.keywords.isEmpty() ? "none" : String.join(", ", CONFIG.whisperReply.keywords))
                + (CONFIG.whisperReply.publicChat ? " | whisper or public chat" : " | whisper only")
                + (CONFIG.whisperReply.antiSpam ? " | anti spam" : "")
                + " | whitelisted players only")
            .addField("Priority", CONFIG.priority);
    }
}
