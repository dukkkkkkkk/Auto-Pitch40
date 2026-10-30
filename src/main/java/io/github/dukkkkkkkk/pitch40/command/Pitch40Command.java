package io.github.dukkkkkkkk.pitch40.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.discord.Embed;
import io.github.dukkkkkkkk.pitch40.Pitch40Config.RocketMode;
import io.github.dukkkkkkkk.pitch40.module.Pitch40Module;

import static com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg;
import static com.mojang.brigadier.arguments.DoubleArgumentType.getDouble;
import static com.mojang.brigadier.arguments.FloatArgumentType.floatArg;
import static com.mojang.brigadier.arguments.FloatArgumentType.getFloat;
import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.zenith.Globals.MODULE;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;
import static io.github.dukkkkkkkk.pitch40.Pitch40Plugin.CONFIG;

public class Pitch40Command extends Command {
    private static final double MIN_Y = -128;
    private static final double MAX_Y = 512;

    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("pitch40")
            .category(CommandCategory.MODULE)
            .description("""
                Pitch40 elytra travel for the bot.

                Dives at +40 pitch until the lower bound, then climbs at -40 until the upper bound.
                Start it while gliding at or above the upper bound (or anywhere if carrying rockets).

                Rockets:
                  off - free glide only
                  keep - a rocket only when a climb tops out more than heightTolerance under the upper bound
                  boost - keep, plus a rocket every Nth climb

                Chest swap keeps elytra durability loss near zero by swapping a hotbar chestplate on and off.

                Only runs while no player is controlling the proxy, and pauses while the pathfinder is active.
                """)
            .usageLines(
                "on/off",
                "bounds <lower> <upper>",
                "rotationSpeed <degrees per tick>",
                "yaw <degrees>/off",
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
                "priority <priority>"
            )
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("pitch40")
            .then(argument("toggle", toggle()).executes(c -> {
                CONFIG.enabled = getToggle(c, "toggle");
                MODULE.get(Pitch40Module.class).syncEnabledFromConfig();
                c.getSource().getEmbed()
                    .title("Pitch40 " + toggleStrCaps(CONFIG.enabled));
            }))
            .then(corridorCommands())
            .then(literal("rotationSpeed").then(argument("degrees", floatArg(0.5f, 20f)).executes(c -> {
                CONFIG.corridor.rotationSpeed = getFloat(c, "degrees");
                c.getSource().getEmbed()
                    .title("Rotation Speed Set");
            })))
            .then(literal("yaw")
                .then(literal("off").executes(c -> {
                    CONFIG.corridor.lockYaw = false;
                    c.getSource().getEmbed()
                        .title("Yaw Lock Off");
                }))
                .then(argument("degrees", floatArg(-180f, 180f)).executes(c -> {
                    CONFIG.corridor.lockYaw = true;
                    CONFIG.corridor.yaw = getFloat(c, "degrees");
                    c.getSource().getEmbed()
                        .title("Yaw Locked");
                })))
            .then(rocketCommands())
            .then(elytraCommands())
            .then(chestSwapCommands())
            .then(literal("priority").then(argument("priority", integer()).executes(c -> {
                CONFIG.priority = getInteger(c, "priority");
                c.getSource().getEmbed()
                    .title("Priority Set");
            })));
    }

    private LiteralArgumentBuilder<CommandContext> corridorCommands() {
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
            .addField("Bounds", corridor.lowerBound + " - " + corridor.upperBound)
            .addField("Rotation Speed", corridor.rotationSpeed + " deg/tick")
            .addField("Yaw", corridor.lockYaw ? String.valueOf(corridor.yaw) : "current heading")
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
            .addField("Priority", CONFIG.priority);
    }
}
