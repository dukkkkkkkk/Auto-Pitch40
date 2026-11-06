package io.github.dukkkkkkkk.pitch40;

import java.util.ArrayList;
import java.util.List;

/**
 * Plugin configuration, saved to {@code plugins/config/pitch40.json}.
 *
 * <p>Fields must stay public and mutable; ZenithProxy serializes them directly.
 * Nested classes become nested JSON objects.
 */
public class Pitch40Config {
    public boolean enabled = false;

    /** Input priority. Must beat AntiAFK (5000). KillAura (8000) and AutoEat (11000) still win over it. */
    public int priority = 7500;

    public final Corridor corridor = new Corridor();
    public final Rockets rockets = new Rockets();
    public final Elytra elytra = new Elytra();
    public final ChestSwap chestSwap = new ChestSwap();
    public final AutoLog autoLog = new AutoLog();
    public final Launch launch = new Launch();
    public final Regear regear = new Regear();
    public final CoordReport coordReport = new CoordReport();
    public final WhisperReply whisperReply = new WhisperReply();

    public static final class Corridor {
        /** Below this Y the bot pitches up to climb. */
        public double lowerBound = 360.0;
        /** Above this Y the bot pitches down to dive and regain speed. Ignored when {@link #ceiling} is off. */
        public double upperBound = 420.0;
        /**
         * Use the upper bound. With it off there is no height limit: each climb runs until it tops out,
         * then the bot dives {@link #diveDepth} blocks under that peak. With boost rockets it keeps gaining height.
         */
        public boolean ceiling = true;
        /** No ceiling only: how far under the last peak each dive goes before climbing again. */
        public double diveDepth = 60.0;
        /**
         * No ceiling only: each climb aims this many blocks above the best peak so far. A climb that falls more
         * than heightTolerance short gets a rocket on the next one, so the bot keeps gaining height. 0 just holds
         * the best height. Needs rockets on (keep or boost).
         */
        public double climbGain = 20.0;
        /** Degrees of pitch change per tick while swinging between -40 and +40. */
        public float rotationSpeed = 4.0f;
        /**
         * Start each turn early by however far the last one overshot, so the swing stays between the bounds.
         * Without it the bot rises about 24 blocks past the upper bound before diving (90 block dives with
         * 360-420 bounds instead of 60). Costs about 10% average speed, since smaller swings build less speed.
         */
        public boolean stayInBounds = true;
        /** Hold a fixed heading instead of keeping the current yaw. */
        public boolean lockYaw = false;
        public float yaw = 0.0f;
        /** The quadrant the yaw was locked to with {@code pitch40 quad set}, like "++" or "0-". Empty if none. */
        public String quad = "";
    }

    public static final class Rockets {
        public RocketMode mode = RocketMode.KEEP;
        /** How far under the upper bound a climb may top out before the next climb spends a rocket. */
        public double heightTolerance = 10.0;
        /** BOOST only: fire a rocket on every Nth climb. */
        public int climbsPerRocket = 1;
        /** Least time between rockets, except launches and stalls. Stops it burning through rockets. */
        public int minSecondsBetween = 30;
        /** Hotbar slot (0-8) rockets are moved into when none are held. Read at startup. */
        public int hotbarSlot = 8;
    }

    public enum RocketMode {
        /** Free glide only. */
        OFF,
        /** A rocket on the next climb whenever a climb tops out more than heightTolerance short of the upper bound. */
        KEEP,
        /** KEEP, plus a rocket every {@link Rockets#climbsPerRocket} climbs for speed. */
        BOOST
    }

    public static final class Elytra {
        /** Press jump while falling to (re)open the elytra. */
        public boolean autoDeploy = true;
        /** Swap the worn elytra for the spare with the most durability when it gets low. */
        public boolean autoReplace = true;
        /** Durability left at which the worn elytra is replaced. Minimum 2; an elytra stops working at 1. */
        public int replaceDurability = 10;
        /** A spare needs at least this much durability left before the bot will swap to it. */
        public int minSpareDurability = 10;
        /**
         * Disconnect as soon as the bot swaps onto its last elytra, so you keep one full elytra to fly home with.
         * Needs autoReplace and at least one spare to start with.
         */
        public boolean lastElytra = true;
        /** Warn when the worn elytra is this low and there is no spare to replace it. */
        public int lowDurabilityWarning = 20;
    }

    public static final class ChestSwap {
        /** Keep elytra durability loss near zero by resetting the server's glide counter. */
        public boolean enabled = false;
        /** quick: elytra stays on, a same-tick swap and glide restart every intervalTicks. dukway: chestplate on for
         * intervalTicks, then the elytra for 1 tick with a glide start. */
        public ChestSwapMode mode = ChestSwapMode.QUICK;
        /** Ticks between swaps. Must stay under 20, when gliding starts costing durability. */
        public int intervalTicks = 4;
        /** Hotbar slot (0-8) a chestplate is moved into if none is in the hotbar. Never the rocket slot. */
        public int hotbarSlot = 7;
        /**
         * Stop swapping while a rocket is boosting, keeping the elytra on for the whole boost, then carry on.
         * Restarting the glide mid-boost can cut it short.
         */
        public boolean pauseForRockets = true;
    }

    public enum ChestSwapMode {
        QUICK,
        DUKWAY
    }

    public static final class AutoLog {
        /**
         * Disconnect when the bot glides or falls too far under the corridor, or falls toward the void. Checked every
         * tick the bot is in control, not just mid-flight.
         */
        public boolean enabled = true;
        /**
         * How far under the lower bound the bot may sink before disconnecting.
         * The default leaves room for the emergency rockets (10 and 30 under) to recover first.
         */
        public double blocksBelowLowerBound = 40.0;
        /** Stop AutoReconnect from logging straight back in afterwards. */
        public boolean cancelReconnect = true;
        /**
         * Disconnect by sending packets the server always kicks for (attacking yourself, an invalid position), so
         * the server ends the session itself, with a normal disconnect a second later as a fallback.
         */
        public boolean illegalDisconnect = true;
    }

    public static final class Regear {
        /**
         * When the bot is down to its second-to-last good elytra, land, place the shulker of XP bottles from the
         * inventory, take XP out, mend the elytras (they need Mending), mine the shulker back up and take off.
         */
        public boolean enabled = true;
        /** Rockets to keep when putting spare rockets into the shulker to make room for XP. */
        public int keepRockets = 128;
    }

    public static final class Launch {
        /** After the proxy connects, take off and rocket up to the upper bound, then start Pitch40. */
        public boolean enabled = true;
        /** Seconds to wait after connecting before taking off, so the world around the bot has loaded. */
        public int delaySeconds = 5;
        /** Degrees above level to climb at while rocketing up (10-90). */
        public float pitch = 60.0f;
    }

    public static final class CoordReport {
        /** Post the bot's coordinates to Discord and the terminal on an interval. */
        public boolean enabled = true;
        public double intervalHours = 24.0;

        // set by the plugin to time the next report and work out distance travelled; no need to edit
        public long lastReportEpochMs = 0L;
        public double lastX = 0.0;
        public double lastZ = 0.0;
        /** Empty until the first report. */
        public String lastDimension = "";
    }

    public static final class WhisperReply {
        /** Whisper the position back to whitelisted players who whisper one of the keywords. */
        public boolean enabled = true;
        /** Matched as whole words anywhere in the message, not case sensitive. */
        public List<String> keywords = new ArrayList<>(List.of("eta", "current"));
        /** Also answer a keyword said in public chat. The answer is still a whisper. */
        public boolean publicChat = true;
        /** Add a random [xxxxx] tag so the server's duplicate-message filter doesn't drop repeat replies. */
        public boolean antiSpam = true;
    }
}
