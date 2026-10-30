package io.github.dukkkkkkkk.pitch40;

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

    public static final class Corridor {
        /** Below this Y the bot pitches up to climb. */
        public double lowerBound = 360.0;
        /** Above this Y the bot pitches down to dive and regain speed. */
        public double upperBound = 420.0;
        /** Degrees of pitch change per tick while swinging between -40 and +40. */
        public float rotationSpeed = 4.0f;
        /** Hold a fixed heading instead of keeping the current yaw. */
        public boolean lockYaw = false;
        public float yaw = 0.0f;
    }

    public static final class Rockets {
        public RocketMode mode = RocketMode.KEEP;
        /** How far under the upper bound a climb may top out before the next climb spends a rocket. */
        public double heightTolerance = 10.0;
        /** BOOST only: fire a rocket on every Nth climb. */
        public int climbsPerRocket = 1;
        /** Hotbar slot (0-8) rockets are moved into when none are held. Read at startup. */
        public int hotbarSlot = 8;
    }

    public enum RocketMode {
        /** Free glide only. */
        OFF,
        /** A rocket only when the cycle sinks below the corridor. */
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
        /** Warn when the worn elytra is this low and there is no spare to replace it. */
        public int lowDurabilityWarning = 20;
    }

    public static final class ChestSwap {
        /** Keep elytra durability loss near zero by resetting the server's glide counter. */
        public boolean enabled = false;
        /** Ticks between swaps. Must stay under 20, when gliding starts costing durability. */
        public int intervalTicks = 4;
        /** Hotbar slot (0-8) a chestplate is moved into if none is in the hotbar. Never the rocket slot. */
        public int hotbarSlot = 7;
    }

    public static final class AutoLog {
        /** Disconnect when a flight sinks too far under the corridor. */
        public boolean enabled = true;
        /**
         * How far under the lower bound the bot may sink before disconnecting.
         * The default leaves room for the emergency rockets (10 and 30 under) to recover first.
         */
        public double blocksBelowLowerBound = 40.0;
        /** Stop AutoReconnect from logging straight back in afterwards. */
        public boolean cancelReconnect = true;
    }
}
