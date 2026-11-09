package io.github.dukkkkkkkk.pitch40.util;

import com.zenith.Proxy;
import com.zenith.event.client.ClientOnlineEvent;
import io.github.dukkkkkkkk.pitch40.module.Pitch40Module;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.*;
import static io.github.dukkkkkkkk.pitch40.Pitch40Plugin.CONFIG;
import static io.github.dukkkkkkkk.pitch40.Pitch40Plugin.LOG;

/**
 * {@code pitch40 startWhenConnected <seconds>}: a set time after the bot gets into the server (past the queue), turns
 * Pitch40 on if it's off and force starts it: launches from the ground, opens the elytra in the air, and starts the
 * cycle from a glide even below the starting height.
 *
 * <p>Listens whether Pitch40 is on or off, since the module's own events only run while it's on.
 */
public final class StartOnConnect {
    /** Bumped on every connect, so a timer from an earlier connection does nothing. */
    private static final AtomicInteger SESSION = new AtomicInteger();

    private StartOnConnect() {}

    /** Call once at plugin load. */
    public static void start() {
        EVENT_BUS.subscribe(SESSION, of(ClientOnlineEvent.class, e -> onOnline()));
    }

    private static void onOnline() {
        int session = SESSION.incrementAndGet();
        if (!CONFIG.startWhenConnected.enabled) return;
        int delay = Math.max(0, CONFIG.startWhenConnected.delaySeconds);
        LOG.info("Connected: force starting Pitch40 in {}s", delay);
        EXECUTOR.schedule(() -> startNow(session), delay, TimeUnit.SECONDS);
    }

    private static void startNow(int session) {
        try {
            if (session != SESSION.get() || !CONFIG.startWhenConnected.enabled) return;
            var proxy = Proxy.getInstance();
            // disconnected (or back in the queue) since
            if (!proxy.isConnected() || proxy.isInQueue()) return;
            forceStartPitch40();
            LOG.info("Pitch40 force started {}s after connecting", CONFIG.startWhenConnected.delaySeconds);
        } catch (Throwable e) {
            LOG.error("Couldn't start Pitch40 after connecting", e);
        }
    }

    /** Turns Pitch40 on if it's off, then force starts it. */
    public static void forceStartPitch40() {
        var module = MODULE.get(Pitch40Module.class);
        if (!CONFIG.enabled) {
            CONFIG.enabled = true;
            module.syncEnabledFromConfig();
            saveConfigAsync();
        }
        module.forceStart();
    }
}
