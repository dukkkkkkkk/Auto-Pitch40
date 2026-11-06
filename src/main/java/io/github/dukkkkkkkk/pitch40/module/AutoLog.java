package io.github.dukkkkkkkk.pitch40.module;

import com.zenith.Proxy;
import com.zenith.feature.player.World;
import com.zenith.event.module.AutoReconnectDecisionEvent;

import static com.zenith.Globals.BOT;
import static io.github.dukkkkkkkk.pitch40.Pitch40Plugin.CONFIG;

/**
 * Disconnects before the bot glides or falls to its death.
 *
 * <p>Checked every tick the bot is in control while Pitch40 is on, whatever it's doing: flying, waiting to start,
 * launching, or with the pathfinder in charge. Two checks:
 * <ul>
 *   <li><b>Too low:</b> gliding, or falling fast, under {@code blocksBelowLowerBound} under the lower bound.
 *       Skipped while a launch is taking off from the ground, which starts below it on purpose.</li>
 *   <li><b>Void:</b> falling within {@value #VOID_MARGIN} blocks of the bottom of the world. Always on (with auto
 *       log), whatever the bounds are set to.</li>
 * </ul>
 * The default trigger leaves room for the bot to recover first (it climbs back up, or rockets out of a stall).
 */
final class AutoLog {
    /** Marks our disconnects so the auto-reconnect can be cancelled for them, like ZenithProxy's AutoDisconnect. */
    private static final String REASON_PREFIX = "[Pitch40] ";
    /** Disconnect when falling within this many blocks of the dimension's lowest Y. */
    static final int VOID_MARGIN = 16;
    /** Falling faster than this (blocks/tick, about 8 blocks of free fall) counts as falling, not just a jump. */
    private static final double FREE_FALL_SPEED = -1.0;

    private final Pitch40Module module;

    AutoLog(Pitch40Module module) {
        this.module = module;
    }

    /** The Y below which a glide or fall disconnects. */
    static double triggerY() {
        return CONFIG.corridor.lowerBound - CONFIG.autoLog.blocksBelowLowerBound;
    }

    /**
     * Call every tick the bot is in control.
     *
     * @param launching a launch is taking off, so being low is expected
     * @return true if it disconnected
     */
    boolean tick(boolean launching) {
        if (!CONFIG.autoLog.enabled || BOT.isOnGround()) return false;
        double y = BOT.getY();
        double velocityY = BOT.getVelocity().getY();

        var dimension = World.getCurrentDimension();
        if (dimension != null && velocityY < 0 && y < dimension.minY() + VOID_MARGIN) {
            module.warn("Falling into the void at Y {}, disconnecting", (int) y);
            disconnect("Falling into the void at Y " + (int) y);
            return true;
        }

        if (launching) return false;
        boolean falling = BOT.isFallFlying() || velocityY < FREE_FALL_SPEED;
        double trigger = triggerY();
        if (!falling || y >= trigger) return false;
        module.warn("Y {} is under the auto log height ({}), disconnecting", (int) y, (int) trigger);
        disconnect("Fell to Y " + (int) y + ", under the auto log height " + (int) trigger);
        return true;
    }

    /** Disconnects with a reason marked as ours, so {@link #onAutoReconnectDecision} can cancel the reconnect. */
    void disconnect(String reason) {
        Proxy.getInstance().disconnect(REASON_PREFIX + reason);
    }

    /** Stops AutoReconnect logging straight back in after one of our disconnects. */
    void onAutoReconnectDecision(AutoReconnectDecisionEvent event) {
        if (!CONFIG.autoLog.cancelReconnect) return;
        String reason = event.getDisconnectEvent().reason();
        if (reason != null && reason.startsWith(REASON_PREFIX)) {
            event.setCancelled(true);
        }
    }
}
