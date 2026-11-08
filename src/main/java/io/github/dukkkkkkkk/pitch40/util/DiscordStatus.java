package io.github.dukkkkkkkk.pitch40.util;

import com.zenith.Proxy;
import com.zenith.feature.queue.Queue;
import com.zenith.module.impl.AutoReconnect;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static com.zenith.Globals.*;
import static io.github.dukkkkkkkk.pitch40.Pitch40Plugin.CONFIG;
import static io.github.dukkkkkkkk.pitch40.Pitch40Plugin.LOG;

/**
 * The Discord bot's status, switching every 30 seconds between distance, e.g. "2b2t [01:05:12] | 12,345 blocks",
 * and the current coords, e.g. "2b2t [01:05:30] | -1300123, 1502, -29999951".
 *
 * <p>ZenithProxy refreshes the status itself every 15 seconds, which would overwrite this, so while it's on this
 * switches off ZenithProxy's {@code managePresence} and switches it back on when turned off. It shows the queue,
 * reconnecting and disconnected states the same way ZenithProxy does.
 *
 * <p>Distance is horizontal blocks travelled since the bot last connected, summed from a position sample every
 * 5 seconds. Jumps too big to be flying (teleports, dimension changes) aren't counted.
 */
public final class DiscordStatus {
    private static final long SAMPLE_SECONDS = 5;
    /** 30 seconds per status, so distance and coords each show once a minute. */
    private static final int SAMPLES_PER_UPDATE = 6;
    /** More than this between samples isn't flying: about 3x the fastest rocket-boosted elytra. */
    private static final double MAX_BLOCKS_PER_SAMPLE = 100.0 * SAMPLE_SECONDS;

    private static double distance = 0.0;
    private static double lastX, lastZ;
    private static String lastDimension = "";
    private static boolean havePosition = false;
    private static boolean wasConnected = false;
    private static int samples = 0;
    private static boolean showCoordsNext = false;

    private DiscordStatus() {}

    /** Call once at plugin load. */
    public static void start() {
        EXECUTOR.scheduleWithFixedDelay(DiscordStatus::tick, SAMPLE_SECONDS, SAMPLE_SECONDS, TimeUnit.SECONDS);
    }

    /** Horizontal blocks travelled since the bot last connected. */
    public static synchronized double sessionDistance() {
        return distance;
    }

    private static synchronized void tick() {
        try {
            sample();
            if (++samples >= SAMPLES_PER_UPDATE) {
                samples = 0;
                updatePresence();
            }
        } catch (Throwable e) {
            LOG.debug("Discord status update failed", e);
        }
    }

    private static void sample() {
        boolean connected = Proxy.getInstance().isConnected() && !Proxy.getInstance().isInQueue();
        if (connected && !wasConnected) {
            // new session
            distance = 0.0;
            havePosition = false;
        }
        wasConnected = connected;
        if (!connected) return;

        var player = CACHE.getPlayerCache();
        double x = player.getX();
        double z = player.getZ();
        var dimensionData = CACHE.getChunkCache().getCurrentDimension();
        String dimension = dimensionData != null ? dimensionData.name() : "";
        if (havePosition && dimension.equals(lastDimension)) {
            double moved = Math.hypot(x - lastX, z - lastZ);
            if (moved <= MAX_BLOCKS_PER_SAMPLE) distance += moved;
        }
        lastX = x;
        lastZ = z;
        lastDimension = dimension;
        havePosition = true;
    }

    private static void updatePresence() {
        var zenithDiscord = com.zenith.Globals.CONFIG.discord;
        if (!CONFIG.discordStatus.enabled) {
            // hand the status back to ZenithProxy if we took it
            if (CONFIG.discordStatus.tookOverZenithStatus) {
                zenithDiscord.managePresence = true;
                CONFIG.discordStatus.tookOverZenithStatus = false;
                saveConfigAsync();
            }
            return;
        }
        if (zenithDiscord.managePresence) {
            zenithDiscord.managePresence = false;
            CONFIG.discordStatus.tookOverZenithStatus = true;
            saveConfigAsync();
        }

        var proxy = Proxy.getInstance();
        if (MODULE.get(AutoReconnect.class).autoReconnectIsInProgress()) {
            DISCORD.setPresence(OnlineStatus.IDLE, Activity.customStatus("AutoReconnecting..."));
        } else if (proxy.isInQueue()) {
            DISCORD.setPresence(OnlineStatus.IDLE, Activity.customStatus(Queue.queuePositionStr()));
        } else if (proxy.isConnected()) {
            String server = proxy.isOn2b2t() ? "2b2t" : com.zenith.Globals.CONFIG.client.server.address;
            boolean coords = CONFIG.discordStatus.showCoords && showCoordsNext;
            showCoordsNext = !showCoordsNext;
            DISCORD.setPresence(OnlineStatus.ONLINE, Activity.customStatus(
                server + " [" + proxy.getOnlineTimeString() + "] | " + (coords ? formatCoords() : formatDistance(distance))));
        } else {
            DISCORD.setPresence(OnlineStatus.DO_NOT_DISTURB, Activity.customStatus("Disconnected"));
        }
    }

    /** "-1300123, 1502, -29999951", with the dimension added outside the overworld. */
    private static String formatCoords() {
        var player = CACHE.getPlayerCache();
        String coords = Math.round(player.getX()) + ", " + Math.round(player.getY()) + ", " + Math.round(player.getZ());
        var dimensionData = CACHE.getChunkCache().getCurrentDimension();
        String dimension = dimensionData != null ? dimensionData.name() : "";
        if (dimension.contains("nether")) return coords + " (nether)";
        if (dimension.contains("end")) return coords + " (end)";
        return coords;
    }

    /** "850 blocks", "12,345 blocks", "1.23M blocks". */
    public static String formatDistance(double blocks) {
        long rounded = Math.round(blocks);
        if (rounded >= 1_000_000) return String.format(Locale.ROOT, "%.2fM blocks", blocks / 1_000_000.0);
        return String.format(Locale.ROOT, "%,d blocks", rounded);
    }
}
