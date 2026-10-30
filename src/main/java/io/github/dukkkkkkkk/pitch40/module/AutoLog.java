package io.github.dukkkkkkkk.pitch40.module;

import com.zenith.Proxy;
import com.zenith.event.module.AutoReconnectDecisionEvent;

import static com.zenith.Globals.BOT;
import static io.github.dukkkkkkkk.pitch40.Pitch40Plugin.CONFIG;

/**
 * Disconnects when a flight sinks too far under the corridor, before the bot can glide into the ground.
 *
 * <p>The trigger is relative to the lower bound, so it moves with the corridor. The default leaves room
 * for the emergency rockets (10 and 30 blocks under the lower bound) to try a recovery first.
 */
final class AutoLog {
    /** Marks our disconnects so the auto-reconnect can be cancelled for them, like ZenithProxy's AutoDisconnect. */
    private static final String REASON_PREFIX = "[Pitch40] ";

    private final Pitch40Module module;

    AutoLog(Pitch40Module module) {
        this.module = module;
    }

    /** The Y below which a flight disconnects. */
    static double triggerY() {
        return CONFIG.corridor.lowerBound - CONFIG.autoLog.blocksBelowLowerBound;
    }

    /**
     * Call once per tick while a flight is running.
     *
     * @return true if it disconnected
     */
    boolean tick() {
        if (!CONFIG.autoLog.enabled) return false;
        double y = BOT.getY();
        double trigger = triggerY();
        if (y >= trigger) return false;
        module.warn("Y {} is under the auto log height ({}), disconnecting", (int) y, (int) trigger);
        Proxy.getInstance().disconnect(REASON_PREFIX + "Fell to Y " + (int) y + ", under the auto log height " + (int) trigger);
        return true;
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
