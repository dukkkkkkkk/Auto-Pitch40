package io.github.dukkkkkkkk.pitch40.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.discord.Embed;
import com.zenith.event.chat.PublicChatEvent;
import com.zenith.event.chat.WhisperChatEvent;
import com.zenith.event.client.ClientTickEvent;
import com.zenith.module.api.Module;
import com.zenith.util.ChatUtil;
import com.zenith.util.timer.Timer;
import com.zenith.util.timer.Timers;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.*;
import static io.github.dukkkkkkkk.pitch40.Pitch40Plugin.CONFIG;

/**
 * Tells you where the bot is.
 *
 * <ul>
 *   <li><b>Report:</b> posts the coordinates to Discord (and the terminal) every {@code intervalHours}. The last report
 *       time is saved in the config, so restarts don't reset the timer, and a report that came due while offline
 *       goes out as soon as the bot is back in game. Respects ZenithProxy's {@code discord reportCoords} setting.</li>
 *   <li><b>Whisper reply:</b> whisper the bot one of the keywords (default "eta" or "current"), or say it in public
 *       chat, and it whispers back its position, speed and Pitch40 state, with a random [xxxxx] tag so the server's
 *       spam filter doesn't drop repeats. Only players on ZenithProxy's whitelist get an answer, since they can
 *       already log into the proxy and see where it is. The answer is always a whisper, never public chat.</li>
 * </ul>
 */
public class CoordsModule extends Module {
    private static final long CHECK_INTERVAL_TICKS = 20L;
    /** Speed is averaged over this many one-second position samples. */
    private static final int SPEED_SAMPLES = 5;
    /** Per player, so a burst of whispers can't get the bot kicked for chat spam. */
    private static final Duration WHISPER_COOLDOWN = Duration.ofSeconds(5);
    private static final int ANTI_SPAM_LENGTH = 5;
    private static final String ANTI_SPAM_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final Timer checkTimer = Timers.tickTimer();
    private final ArrayDeque<double[]> recentPositions = new ArrayDeque<>();
    private final Map<UUID, Instant> lastWhisperReply = new HashMap<>();

    @Override
    public boolean enabledSetting() {
        // stays on if either feature is, each checks its own setting
        return CONFIG.coordReport.enabled || CONFIG.whisperReply.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            // ClientTickEvent runs whenever the bot is in game, whether or not a player is connected
            of(ClientTickEvent.class, this::onTick),
            of(ClientTickEvent.Starting.class, e -> clearPositions()),
            of(WhisperChatEvent.class, this::onWhisper),
            of(PublicChatEvent.class, this::onPublicChat)
        );
    }

    private void onTick(ClientTickEvent event) {
        if (!checkTimer.tick(CHECK_INTERVAL_TICKS)) return;
        samplePosition();
        if (CONFIG.coordReport.enabled && !timeUntilNextReport().isPositive()) {
            report("Daily coords");
        }
    }

    // ---- scheduled report ----

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
        double z = player.getZ();
        String dimension = dimension();
        String moved = movedSinceLastReport(x, z, dimension);

        var report = CONFIG.coordReport;
        report.lastReportEpochMs = System.currentTimeMillis();
        report.lastX = x;
        report.lastZ = z;
        report.lastDimension = dimension;
        saveConfigAsync();

        // ZenithProxy's own config; CONFIG here is the plugin's
        if (!com.zenith.Globals.CONFIG.discord.reportCoords) {
            info("{}: {} in {} ({})", title, position(), dimension, moved);
            discordNotification(Embed.builder()
                .title(title)
                .description("Coordinates are hidden because `discord reportCoords` is off. They're in the terminal.")
                .addField("Moved", moved)
                .primaryColor());
            return;
        }
        discordNotification(Embed.builder()
            .title(title)
            .addField("Position", position())
            .addField("Dimension", dimension)
            .addField("Speed", speedText())
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

    // ---- whisper reply ----

    private void onWhisper(WhisperChatEvent event) {
        if (event.outgoing()) return;
        onKeywordRequest(event.sender(), event.message(), false);
    }

    private void onPublicChat(PublicChatEvent event) {
        if (!CONFIG.whisperReply.publicChat) return;
        onKeywordRequest(event.sender(), event.message(), true);
    }

    /**
     * A whitelisted player sent a keyword, by whisper or in public chat. The answer is always a whisper,
     * so the coordinates never go to public chat.
     */
    private void onKeywordRequest(@Nullable PlayerListEntry sender, @Nullable String message, boolean publicChat) {
        if (!CONFIG.whisperReply.enabled) return;
        if (sender == null || sender.getProfileId() == null || sender.getName() == null) return;
        if (!isKeyword(message)) return;
        // never answer our own messages
        if (sender.getName().equalsIgnoreCase(com.zenith.Globals.CONFIG.authentication.username)) return;
        if (!PLAYER_LISTS.getWhitelist().contains(sender.getProfileId())) {
            // anyone can say "eta" in public chat, so only log the whispers
            if (!publicChat) info("Ignoring \"{}\" from {}: not on the whitelist", message.trim(), sender.getName());
            return;
        }
        var now = Instant.now();
        var last = lastWhisperReply.get(sender.getProfileId());
        if (last != null && Duration.between(last, now).compareTo(WHISPER_COOLDOWN) < 0) return;
        lastWhisperReply.put(sender.getProfileId(), now);

        String reply = whisperReply();
        if (CONFIG.whisperReply.antiSpam) reply += " [" + antiSpamTag() + "]";
        sendClientPacketAsync(ChatUtil.getWhisperChatPacket(sender.getName(), reply));
        info("Whispered position to {} ({})", sender.getName(), publicChat ? "asked in public chat" : "asked by whisper");
    }

    /**
     * Random 5 characters, so repeated replies are never identical and the server's duplicate-message
     * spam filter doesn't drop them.
     */
    private static String antiSpamTag() {
        var random = ThreadLocalRandom.current();
        var tag = new StringBuilder(ANTI_SPAM_LENGTH);
        for (int i = 0; i < ANTI_SPAM_LENGTH; i++) {
            tag.append(ANTI_SPAM_CHARS.charAt(random.nextInt(ANTI_SPAM_CHARS.length())));
        }
        return tag.toString();
    }

    private static boolean isKeyword(String message) {
        if (message == null) return false;
        String word = message.trim().toLowerCase(Locale.ROOT);
        for (String keyword : CONFIG.whisperReply.keywords) {
            if (word.equals(keyword.trim().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /** One line, kept short for the chat length limit. */
    private String whisperReply() {
        var pitch40 = MODULE.get(Pitch40Module.class);
        String reply = position() + " " + dimension() + " | " + speedText();
        if (pitch40.isEnabled()) reply += " | " + pitch40.status();
        return reply;
    }

    // ---- position helpers ----

    private static String position() {
        var player = CACHE.getPlayerCache();
        return "XYZ " + Math.round(player.getX()) + " " + Math.round(player.getY()) + " " + Math.round(player.getZ());
    }

    private static String dimension() {
        var dimension = CACHE.getChunkCache().getCurrentDimension();
        return dimension != null ? dimension.name() : "unknown";
    }

    private synchronized void clearPositions() {
        recentPositions.clear();
    }

    /** Called once a second; keeps the last few positions for the speed average. */
    private synchronized void samplePosition() {
        var player = CACHE.getPlayerCache();
        recentPositions.addLast(new double[] {player.getX(), player.getZ()});
        while (recentPositions.size() > SPEED_SAMPLES + 1) recentPositions.removeFirst();
    }

    /** Horizontal speed over the last few seconds, in blocks per second. */
    private synchronized String speedText() {
        if (recentPositions.size() < 2) return "speed unknown";
        double[] oldest = recentPositions.peekFirst();
        double[] newest = recentPositions.peekLast();
        double seconds = recentPositions.size() - 1;
        double speed = Math.hypot(newest[0] - oldest[0], newest[1] - oldest[1]) / seconds;
        return Math.round(speed) + " b/s";
    }
}
