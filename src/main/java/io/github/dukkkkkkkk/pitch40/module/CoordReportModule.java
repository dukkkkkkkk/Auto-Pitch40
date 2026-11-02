package io.github.dukkkkkkkk.pitch40.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.discord.Embed;
import com.zenith.event.client.ClientTickEvent;
import com.zenith.module.api.Module;
import com.zenith.util.timer.Timer;
import com.zenith.util.timer.Timers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.*;
import static io.github.dukkkkkkkk.pitch40.Pitch40Plugin.CONFIG;

/**
 * Posts the bot's coordinates to Discord (and the terminal) every {@code intervalHours}.
 *
 * <p>The last report time is saved in the config, so restarts don't reset the timer. If the proxy was offline
 * when a report was due, it goes out as soon as it's back in game. Respects ZenithProxy's
 * {@code discord reportCoords} setting: with that off, coordinates only go to the terminal.
 */
public class CoordReportModule extends Module {
    private static final long CHECK_INTERVAL_TICKS = 20L;

    private final Timer checkTimer = Timers.tickTimer();

    @Override
    public boolean enabledSetting() {
        return CONFIG.coordReport.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        // ClientTickEvent runs whenever the bot is in game, whether or not a player is connected
        return List.of(of(ClientTickEvent.class, this::onTick));
    }

    private void onTick(ClientTickEvent event) {
        if (!checkTimer.tick(CHECK_INTERVAL_TICKS)) return;
        if (timeUntilNextReport().isPositive()) return;
        report("Daily coords");
    }

    /** How long until the next report is due. Zero or negative means it's due now. */
    public Duration timeUntilNextReport() {
        var interval = Duration.ofMinutes(Math.round(Math.max(0.1, CONFIG.coordReport.intervalHours) * 60));
        var due = Instant.ofEpochMilli(CONFIG.coordReport.lastReportEpochMs).plus(interval);
        return Duration.between(Instant.now(), due);
    }

    /** Sends a report now and restarts the interval. Only call while in game. */
    public void report(String title) {
        var player = CACHE.getPlayerCache();
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        var dimensionData = CACHE.getChunkCache().getCurrentDimension();
        String dimension = dimensionData != null ? dimensionData.name() : "unknown";
        String moved = movedSinceLastReport(x, z, dimension);

        var report = CONFIG.coordReport;
        report.lastReportEpochMs = System.currentTimeMillis();
        report.lastX = x;
        report.lastZ = z;
        report.lastDimension = dimension;
        saveConfigAsync();

        String position = Math.round(x) + ", " + Math.round(y) + ", " + Math.round(z);
        if (!CONFIG.discord.reportCoords) {
            info("{}: {} in {} ({})", title, position, dimension, moved);
            discordNotification(Embed.builder()
                .title(title)
                .description("Coordinates are hidden because `discord reportCoords` is off. They're in the terminal.")
                .addField("Moved", moved)
                .primaryColor());
            return;
        }
        discordNotification(Embed.builder()
            .title(title)
            .addField("Position", position)
            .addField("Dimension", dimension)
            .addField("Moved", moved)
            .addField("Pitch40", MODULE.get(Pitch40Module.class).status())
            .primaryColor());
    }

    private static String movedSinceLastReport(double x, double z, String dimension) {
        var report = CONFIG.coordReport;
        if (report.lastDimension == null || report.lastDimension.isEmpty()) return "first report";
        if (!report.lastDimension.equals(dimension)) return "changed dimension (was " + report.lastDimension + ")";
        long blocks = Math.round(Math.hypot(x - report.lastX, z - report.lastZ));
        return String.format("%,d blocks since the last report", blocks);
    }
}
