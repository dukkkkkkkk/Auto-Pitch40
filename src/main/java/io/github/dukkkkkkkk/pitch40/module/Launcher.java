package io.github.dukkkkkkkk.pitch40.module;

import com.zenith.feature.player.World;
import com.zenith.mc.dimension.DimensionRegistry;
import com.zenith.util.math.MathHelper;
import io.github.dukkkkkkkk.pitch40.util.Items;

import java.time.Duration;
import java.time.Instant;

import static com.zenith.Globals.BOT;
import static io.github.dukkkkkkkk.pitch40.Pitch40Plugin.CONFIG;

/**
 * Takes off and rockets up to the upper bound, so Pitch40 can start on its own after the proxy connects.
 *
 * <p>Steps: wait a few seconds for the world to load, jump, press jump again in the air to open the elytra,
 * then point up at the launch pitch and fire rockets back to back until the upper bound. From there the
 * normal Pitch40 cycle takes over. Spawning mid-air or already gliding skips the steps it doesn't need.
 *
 * <p>Gives up (with a warning) if there's no elytra or rockets, something is right above the bot, the target
 * is above the nether roof, the elytra won't open, or the climb takes too long.
 */
final class Launcher {
    /** Launches still pending this long after connecting are dropped, e.g. if you were controlling the bot. */
    private static final Duration ARM_TIMEOUT = Duration.ofMinutes(2);
    private static final int HEADROOM_BLOCKS = 4;
    private static final int MAX_JUMP_ATTEMPTS = 3;
    private static final int DEPLOY_TIMEOUT_TICKS = 40;
    private static final int CLIMB_TIMEOUT_TICKS = 20 * 90;
    private static final int NETHER_ROOF_Y = 128;

    private enum Phase { IDLE, WAITING, JUMP, OPEN_ELYTRA, CLIMB }

    private final Pitch40Module module;
    // armed from the event thread, ticked on the client thread
    private volatile Phase phase = Phase.IDLE;
    private Instant armedAt = Instant.EPOCH;
    private int ticksInPhase = 0;
    private int jumpAttempts = 0;
    private boolean jumpHeld = false;

    Launcher(Pitch40Module module) {
        this.module = module;
    }

    /** Schedule a launch after the configured delay, e.g. after connecting. */
    void arm() {
        arm(false);
    }

    /** Schedule a launch, optionally skipping the delay. */
    void arm(boolean immediate) {
        armedAt = Instant.now();
        jumpAttempts = 0;
        ticksInPhase = immediate ? Integer.MAX_VALUE / 2 : 0;
        phase = Phase.WAITING;
    }

    void cancel() {
        phase = Phase.IDLE;
    }

    boolean active() {
        return phase != Phase.IDLE;
    }

    /** Physically taking off or climbing (not just waiting to). */
    boolean launching() {
        var current = phase;
        return current == Phase.JUMP || current == Phase.OPEN_ELYTRA || current == Phase.CLIMB;
    }

    String describe() {
        return switch (phase) {
            case IDLE -> "idle";
            case WAITING -> "waiting to launch";
            case JUMP, OPEN_ELYTRA -> "taking off";
            case CLIMB -> "launching to Y " + (int) targetY();
        };
    }

    static double targetY() {
        return CONFIG.corridor.upperBound;
    }

    /**
     * Call once per bot tick while {@link #active()}.
     *
     * @return true if the launch sent this tick's input, so normal flight should skip the tick
     */
    boolean tick() {
        ticksInPhase++;
        return switch (phase) {
            case IDLE -> false;
            case WAITING -> tickWaiting();
            case JUMP -> tickJump();
            case OPEN_ELYTRA -> tickOpenElytra();
            case CLIMB -> tickClimb();
        };
    }

    private boolean tickWaiting() {
        if (Duration.between(armedAt, Instant.now()).compareTo(ARM_TIMEOUT) > 0) {
            phase = Phase.IDLE;
            return false;
        }
        if (ticksInPhase < Math.max(0, CONFIG.launch.delaySeconds) * 20) return false;

        if (BOT.getY() >= targetY()) {
            // already high enough, normal flight takes it from here
            phase = Phase.IDLE;
            return false;
        }
        String problem = launchProblem();
        if (problem != null) {
            module.warn("Not launching: {}", problem);
            phase = Phase.IDLE;
            return false;
        }
        module.info("Launching to Y {}", (int) targetY());
        if (BOT.isFallFlying()) {
            setPhase(Phase.CLIMB);
        } else if (BOT.isOnGround()) {
            setPhase(Phase.JUMP);
        } else {
            setPhase(Phase.OPEN_ELYTRA);
        }
        return false;
    }

    /** Why a launch can't start, or null if it can. */
    private String launchProblem() {
        var chest = Items.chest();
        if (!Items.isElytra(chest) || Items.durabilityLeft(chest) <= 1) return "no usable elytra in the chest slot";
        if (module.rocketCount() == 0) return "no firework rockets";
        var dimension = World.getCurrentDimension();
        if (dimension == DimensionRegistry.THE_NETHER.get() && BOT.getY() < NETHER_ROOF_Y && targetY() >= NETHER_ROOF_Y) {
            return "the upper bound is above the nether roof";
        }
        if (BOT.isOnGround() && !headroomClear()) return "there's a block above the bot";
        return null;
    }

    private static boolean headroomClear() {
        int x = MathHelper.floorI(BOT.getX());
        int z = MathHelper.floorI(BOT.getZ());
        int headY = MathHelper.floorI(BOT.getY()) + 2;
        if (!World.isChunkLoadedBlockPos(x, z)) return false;
        for (int y = headY; y < headY + HEADROOM_BLOCKS; y++) {
            if (!World.getBlock(x, y, z).isAir()) return false;
        }
        return true;
    }

    private boolean tickJump() {
        jumpHeld = true;
        module.submitLaunchInput(false, true, false);
        setPhase(Phase.OPEN_ELYTRA);
        return true;
    }

    private boolean tickOpenElytra() {
        if (BOT.isFallFlying()) {
            setPhase(Phase.CLIMB);
            return tickClimb();
        }
        if (BOT.isOnGround() && ticksInPhase > 5) {
            // came back down without the elytra opening
            if (++jumpAttempts >= MAX_JUMP_ATTEMPTS) return giveUp("the elytra didn't open after " + MAX_JUMP_ATTEMPTS + " jumps");
            setPhase(Phase.JUMP);
            return false;
        }
        if (ticksInPhase > DEPLOY_TIMEOUT_TICKS) return giveUp("the elytra didn't open");
        // gliding starts on a fresh jump press in the air, so alternate it
        jumpHeld = !jumpHeld;
        module.submitLaunchInput(false, !BOT.isOnGround() && jumpHeld, false);
        return true;
    }

    private boolean tickClimb() {
        if (BOT.getY() >= targetY()) {
            module.info("Reached Y {}, starting Pitch40", (int) BOT.getY());
            phase = Phase.IDLE;
            return false;
        }
        if (BOT.isOnGround()) return giveUp("landed during the climb");
        if (!BOT.isFallFlying()) {
            // lost the glide; reopen it and keep climbing
            setPhase(Phase.OPEN_ELYTRA);
            return tickOpenElytra();
        }
        if (ticksInPhase > CLIMB_TIMEOUT_TICKS) return giveUp("still under Y " + (int) targetY() + " after 90 seconds");
        if (module.rocketCount() == 0) return giveUp("ran out of rockets at Y " + (int) BOT.getY());
        module.submitLaunchInput(true, false, true);
        return true;
    }

    private boolean giveUp(String reason) {
        module.warn("Launch stopped: {}", reason);
        phase = Phase.IDLE;
        return false;
    }

    private void setPhase(Phase next) {
        phase = next;
        ticksInPhase = 0;
    }

    /** The pitch to climb at, clamped to sensible values. */
    static float climbPitch() {
        return -Math.clamp(CONFIG.launch.pitch, 10f, 90f);
    }
}
