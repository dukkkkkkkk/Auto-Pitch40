package io.github.dukkkkkkkk.pitch40.module;

import com.zenith.Proxy;
import com.zenith.feature.player.World;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.InteractAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundInteractPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import com.zenith.event.module.AutoReconnectDecisionEvent;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.EVENT_BUS;
import static com.zenith.Globals.EXECUTOR;
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
    /** A server kick this soon after an illegal disconnect counts as ours, for the reconnect block. */
    private static final Duration KICK_WINDOW = Duration.ofSeconds(10);
    /** Normal disconnect this long after the illegal packets, in case the server ignored them. */
    private static final long FALLBACK_DISCONNECT_MS = 1000L;
    private static final Object RECONNECT_GUARD = new Object();
    private static volatile Instant expectKickUntil = Instant.EPOCH;
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

    /** Disconnects (putting the elytra back first), the illegal way if that's on. */
    void disconnect(String reason) {
        // never log out wearing the dukway chestplate mid-air
        module.restoreElytra();
        disconnectNow(reason);
    }

    /**
     * Disconnects now. With {@code illegalDisconnect} on, sends the server things it always kicks a player for,
     * so the server ends the session itself, with a normal disconnect a second later in case it ignores them.
     * One illegal packet is enough; the server handles packets in order, so more wouldn't make it faster.
     */
    static void disconnectNow(String reason) {
        var proxy = Proxy.getInstance();
        if (!proxy.isConnected()) return;
        if (!CONFIG.autoLog.illegalDisconnect) {
            proxy.disconnect(REASON_PREFIX + reason);
            return;
        }
        expectKickUntil = Instant.now().plus(KICK_WINDOW);
        var client = proxy.getClient();
        // attacking yourself gets "invalid_entity_attacked"...
        client.sendAsync(new ServerboundInteractPacket(CACHE.getPlayerCache().getEntityId(), InteractAction.ATTACK, false));
        // ...and a position that isn't a number gets "invalid_player_movement"
        client.sendAsync(new ServerboundMovePlayerPosPacket(false, false, Double.NaN, Double.NaN, Double.NaN));
        EXECUTOR.schedule(() -> {
            if (Proxy.getInstance().isConnected()) Proxy.getInstance().disconnect(REASON_PREFIX + reason);
        }, FALLBACK_DISCONNECT_MS, TimeUnit.MILLISECONDS);
    }

    /** Call once at plugin load: blocks AutoReconnect after our disconnects, whether or not Pitch40 is on. */
    static void registerReconnectGuard() {
        EVENT_BUS.subscribe(RECONNECT_GUARD, of(AutoReconnectDecisionEvent.class, AutoLog::onAutoReconnectDecision));
    }

    /** Stops AutoReconnect logging straight back in after one of our disconnects, including illegal-disconnect kicks. */
    private static void onAutoReconnectDecision(AutoReconnectDecisionEvent event) {
        if (!CONFIG.autoLog.cancelReconnect) return;
        String reason = event.getDisconnectEvent().reason();
        boolean ours = reason != null && reason.startsWith(REASON_PREFIX);
        // an illegal disconnect comes back as a server kick, without our tag
        boolean ourKick = Instant.now().isBefore(expectKickUntil);
        if (ours || ourKick) event.setCancelled(true);
    }
}
