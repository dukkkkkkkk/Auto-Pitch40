package io.github.dukkkkkkkk.pitch40.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.discord.Embed;
import com.zenith.event.client.ClientBotTick;
import com.zenith.event.client.ClientOnlineEvent;
import com.zenith.feature.player.ClickResult;
import com.zenith.feature.player.ClickTarget;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.feature.player.InputRequestFuture;
import com.zenith.mc.dimension.DimensionData;
import com.zenith.module.impl.AbstractInventoryModule;
import com.zenith.util.RequestFuture;
import com.zenith.util.math.MathHelper;
import io.github.dukkkkkkkk.pitch40.Pitch40Config.RocketMode;
import io.github.dukkkkkkkk.pitch40.util.Items;
import io.github.dukkkkkkkk.pitch40.util.Notice;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.*;
import static io.github.dukkkkkkkk.pitch40.Pitch40Plugin.CONFIG;

/**
 * Pitch40 elytra travel for the ZenithProxy bot.
 *
 * <p>The bot swings its pitch between +40 (dive, gain speed) and -40 (climb, trade speed for height):
 * it dives once it reaches the upper bound and climbs once it sinks to the lower bound.
 *
 * <p>Rocket rules, ported from BepHax's Meteor Pitch40 module:
 * <ul>
 *   <li>a rocket is spent on the next climb when a climb tops out more than {@code heightTolerance}
 *       under the upper bound ({@link GlideCycle})</li>
 *   <li>an emergency rocket fires when well under the lower bound and stalling</li>
 *   <li>otherwise rockets only fire while climbing, and only when they add speed along the look direction</li>
 * </ul>
 *
 * <p>With launch on, it also takes off by itself after the proxy connects and rockets up to the upper bound
 * ({@link Launcher}).
 *
 * <p>Only runs while no player is controlling the proxy, and pauses while ZenithProxy's pathfinder is active.
 */
public class Pitch40Module extends AbstractInventoryModule {
    private static final float MAX_PITCH = 40.0f;
    /** A cycle can start this far above the lower bound without rockets, as in Meteor's Pitch40. */
    private static final double START_MARGIN = 40.0;
    private static final int NO_ROCKETS_RETRY_TICKS = 20;
    private static final int CLICK_REJECTED_RETRY_TICKS = 5;
    /** Under the lower bound by this much and stalled: emergency rocket. */
    private static final double EMERGENCY_SAG = 10.0;
    /** Stalled: pitched up, yet falling faster than this (blocks/tick)... */
    private static final double STALL_SINK = 0.3;
    /** ...with less horizontal speed than this (blocks/tick). */
    private static final double STALL_HORIZONTAL_SPEED = 0.5;
    /** Under-band dives stop this far above the too-low zone. */
    private static final double TOO_LOW_MARGIN = 5.0;
    /** At most this far under the lower bound before the bot points up and rockets without waiting. */
    private static final double DEEP_UNDER = 20.0;
    /**
     * How far under the last peak a dive goes while under the band. 40 got back into the band fastest in an
     * elytra physics simulation and kept it there when climbs level off (60 let the whole cycle sink).
     */
    private static final double UNDER_BAND_DIVE = 40.0;
    /** Sinking this fast (blocks/tick) while fully pitched up means the climb has failed. */
    private static final double FAILED_CLIMB_SINK = 0.1;
    /** A climbing rocket is skipped when speed along the look direction is already this high (blocks/tick). */
    private static final double ROCKET_GATE = 0.7;

    private final int rocketHotbarSlot;
    private final GlideCycle cycle = new GlideCycle();
    private final RocketTracker rockets = new RocketTracker();
    private final ElytraManager elytra = new ElytraManager(this);
    private final ChestSwap chestSwap;
    private final AutoLog autoLog = new AutoLog(this);
    private final Launcher launcher = new Launcher(this);
    private final TurnAnticipation turns = new TurnAnticipation();
    private final Regear regear;

    /** A Pitch40 cycle is running. Cleared on landing, dimension change, or disable. */
    private boolean flying = false;
    private boolean pitchingDown = true;
    private float pitch = MAX_PITCH;
    /** No ceiling: the Y the current dive started from (the last peak). */
    private double diveFromY = 0.0;
    private int rocketCooldown = 0;
    /** When the last rocket went off, for the gap between non-emergency rockets. */
    private Instant lastRocketAt = Instant.EPOCH;
    /** Rockets fired this session, by why. */
    private final EnumMap<RocketUse, Integer> rocketUses = new EnumMap<>(RocketUse.class);

    /** Why a rocket was fired, for the status. */
    enum RocketUse {
        LAUNCH("launch"),
        CORRIDOR("holding height"),
        CLIMB("climb gain"),
        BOOST("boost"),
        EMERGENCY("too low");

        final String label;

        RocketUse(String label) {
            this.label = label;
        }
    }
    private boolean jumpHeld = false;
    private RequestFuture rocketMove = RequestFuture.rejected;
    private @Nullable DimensionData lastDimension = null;

    private final Notice waitingToStart = new Notice();
    private final Notice noRockets = new Notice();
    private final Notice lowRockets = new Notice();
    private final Notice outOfRockets = new Notice();
    private int rocketCheckTicks = 0;

    public Pitch40Module() {
        super(HandRestriction.EITHER, configuredRocketSlot());
        this.rocketHotbarSlot = configuredRocketSlot();
        this.chestSwap = new ChestSwap(this, rocketHotbarSlot);
        this.regear = new Regear(this, rocketHotbarSlot);
    }

    private static int configuredRocketSlot() {
        return Math.clamp(CONFIG.rockets.hotbarSlot, 0, 8);
    }

    // ---- module lifecycle ----

    @Override
    public boolean enabledSetting() {
        return CONFIG.enabled;
    }

    @Override
    public int getPriority() {
        return CONFIG.priority;
    }

    /** Which items count as rockets for the hotbar swap in {@link AbstractInventoryModule}. */
    @Override
    public boolean itemPredicate(ItemStack itemStack) {
        return Items.isRocket(itemStack);
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, this::onTick),
            of(ClientBotTick.Starting.class, e -> {
                reset();
                // the bot just took control (connected, or a player left the proxy): take off if it's standing
                // or gliding low. Already above the upper bound, the launch steps aside for normal flight
                if (CONFIG.launch.enabled) launcher.arm();
            }),
            of(ClientBotTick.Stopped.class, e -> {
                reset();
                // a player took over (or we disconnected): don't launch when they leave later
                launcher.cancel();
                regear.cancel();
            }),
            of(ClientOnlineEvent.class, e -> {
                // only when the bot is in control; never surprise a player who is logged in
                if (CONFIG.launch.enabled && !Proxy.getInstance().hasActivePlayer()) launcher.arm();
            })
        );
    }

    @Override
    public void onEnable() {
        reset();
        // turned on while the bot is in control: launch now if needed. With a player logged in,
        // ClientBotTick.Starting arms it when they disconnect
        if (CONFIG.launch.enabled && botInControl()) launcher.arm(true);
    }

    private static boolean botInControl() {
        return Proxy.getInstance().isConnected() && !Proxy.getInstance().hasActivePlayer();
    }

    @Override
    public void onDisable() {
        reset();
        launcher.cancel();
        regear.cancel();
    }

    // ---- tick ----

    private void onTick(ClientBotTick event) {
        if (!CACHE.getPlayerCache().getThePlayer().isAlive()) {
            endFlight();
            return;
        }
        checkDimension();

        // safety first: runs whatever state the bot is in, not just mid-flight. Landing to regear and taking off
        // are low on purpose (the void check still applies)
        if (autoLog.tick(launcher.launching() || regear.active())) {
            endFlight();
            launcher.cancel();
            regear.cancel();
            return;
        }

        checkRocketSupply();

        // landed to regear: it drives the bot until it's done, then take off again
        if (regear.active()) {
            if (!regear.tick()) launcher.arm(true);
            return;
        }

        if (rocketCooldown > 0) rocketCooldown--;
        if (rockets.tick() == RocketTracker.Observation.UNCONFIRMED) {
            debug("Rocket not seen attached after {} ticks, allowing another", RocketTracker.CONFIRM_TIMEOUT_TICKS);
        }

        // the pathfinder flies on its own (e.g. `pathfinder elytra`)
        if (BARITONE.isActive()) {
            cycle.reset();
            return;
        }
        if (launcher.active() && launcher.tick()) return;
        if (BOT.isOnGround()) {
            if (flying) info("Landed, Pitch40 stopped");
            endFlight();
            return;
        }
        // while dukway has the chestplate on, the elytra is in the hotbar on purpose (and a server resync can show it
        // there), so skip the worn-elytra checks until it's back
        if (!chestSwap.holdingChestplate() && !elytra.tick(true)) {
            cycle.reset();
            return;
        }
        // down to the second-to-last good elytra: land and mend them, keeping the last one untouched
        if (flying && regear.shouldStart(elytra.spareCount())) {
            endFlight();
            regear.start("down to the second-to-last good elytra");
            return;
        }
        if (CONFIG.elytra.lastElytra && elytra.reachedLastElytra()) {
            warn("Swapped onto the last elytra, disconnecting to keep it full");
            // no coords in the reason: it shows in Discord even when reportCoords is off
            autoLog.disconnect("Swapped onto the last elytra, disconnected to keep it full");
            endFlight();
            return;
        }
        // dukway keeps the chestplate on most ticks, so the server mostly says "not gliding"; its own glide
        // starts take care of that, so don't treat it as a dropped glide mid-flight
        if (!BOT.isFallFlying() && !(flying && ChestSwap.dukwayOn())) {
            if (flying) {
                // dropped out of the glide mid-flight (the chest swap's glide restart can do this): get straight back
                // in, and keep the climb/dive tracking, which a reset every few ticks would break
                deployElytra(true);
            } else {
                cycle.reset();
                deployElytra(false);
            }
            return;
        }
        if (!flying && !tryStartFlight()) return;

        // sample first so a climb that just topped out can turn into a dive this tick
        cycle.sample(
            BOT.getY(),
            BOT.getVelocity().getY(),
            CONFIG.rockets.mode,
            CONFIG.corridor.ceiling ? CONFIG.corridor.upperBound : GlideCycle.NO_CEILING,
            CONFIG.corridor.climbGain,
            CONFIG.rockets.heightTolerance,
            CONFIG.rockets.climbsPerRocket
        );
        turns.sample(BOT.getY(), BOT.getVelocity().getY(), CONFIG.corridor.upperBound, maxTurnLead());
        steerPitch();
        RocketUse rocketWanted = rocketWanted();
        // a rocket fired with the dukway chestplate on wouldn't boost: put the elytra on this tick, fire next tick
        boolean waitForElytra = rocketWanted != null && chestSwap.holdingChestplate();
        boolean firingRocket = submitFlightInput(waitForElytra ? null : rocketWanted);
        chestSwap.tick(!firingRocket && !waitForElytra && !boostingWithRocket() && !sinking()
            && !elytra.busy() && !elytra.needsReplacement(), !rocketMove.isCompleted());
    }

    private void checkDimension() {
        var dimension = CACHE.getChunkCache().getCurrentDimension();
        if (dimension == lastDimension) return;
        if (lastDimension != null && flying) info("Dimension changed, restarting Pitch40");
        lastDimension = dimension;
        endFlight();
    }

    // ---- flight ----

    /** Presses jump while falling so the bot starts gliding again. */
    /** @param midFlight a flight is running, so re-open straight away instead of waiting to fall */
    private void deployElytra(boolean midFlight) {
        if (!CONFIG.elytra.autoDeploy) return;
        // otherwise only while actually falling, so jumping off something still behaves normally
        if (!midFlight && BOT.getVelocity().getY() >= -GlideCycle.VELOCITY_DEADBAND) return;
        if (BOT.isTouchingWater() || BOT.isTouchingLava()) return;
        // gliding starts on a fresh jump press, so alternate it each tick
        jumpHeld = !jumpHeld;
        INPUTS.submit(InputRequest.builder()
            .owner(this)
            .input(Input.builder().jumping(jumpHeld).build())
            .priority(getPriority())
            .build());
    }

    private boolean tryStartFlight() {
        if (!canStartFlight()) {
            if (waitingToStart.show()) {
                int startY = (int) (CONFIG.corridor.ceiling
                    ? Math.max(CONFIG.corridor.upperBound, CONFIG.corridor.lowerBound + START_MARGIN)
                    : CONFIG.corridor.lowerBound + START_MARGIN);
                info("Waiting: glide at or above Y {} to start Pitch40, or carry rockets", startY);
            }
            return false;
        }
        flying = true;
        waitingToStart.clear();
        double y = BOT.getY();
        // always start with a dive: it builds the speed the first climb needs
        pitchingDown = true;
        diveFromY = y;
        pitch = MathHelper.clamp(BOT.getPitch(), -MAX_PITCH, MAX_PITCH);
        cycle.reset();
        turns.reset();
        info("Pitch40 started at Y {} (rockets: {}{})", (int) y, CONFIG.rockets.mode.name().toLowerCase(),
            CONFIG.corridor.ceiling ? "" : ", no ceiling");
        return true;
    }

    private boolean canStartFlight() {
        var corridor = CONFIG.corridor;
        double y = BOT.getY();
        boolean aboveFloor = y - START_MARGIN >= corridor.lowerBound;
        if (aboveFloor && (!corridor.ceiling || y >= corridor.upperBound)) return true;
        // with rockets the corridor can be climbed back into from anywhere
        return CONFIG.rockets.mode != RocketMode.OFF && rocketCount() > 0;
    }

    /**
     * Picks dive or climb, then turns the pitch toward +40 or -40.
     *
     * <p>A dive runs down to the lower bound (with no ceiling, {@code diveDepth} under the last peak if that comes
     * first), then the bot climbs. A climb ends at the upper bound, or as soon as it tops out or stalls, whichever
     * comes first. Without that, a climb that ran out of speed short of the upper bound would hang at -40 and sink
     * all the way down while still "climbing". The lower bound always forces a climb.
     */
    private void steerPitch() {
        var corridor = CONFIG.corridor;
        double y = BOT.getY();
        boolean anticipate = CONFIG.corridor.stayInBounds;
        if (tooLowToWait()) {
            // too far under the band to keep cycling: point up and let the rockets lift it
            pitchingDown = false;
        } else if (pitchingDown) {
            // where this dive should bottom out: the lower bound, or with no ceiling diveDepth under the peak.
            // Under the band, a short dive under the peak: enough speed to climb, without sinking the whole cycle
            // ...but never into the too-low zone, where every dive would set off emergency rockets
            double target = diveFromY <= corridor.lowerBound
                ? Math.max(diveFromY - Math.min(corridor.diveDepth, UNDER_BAND_DIVE), deepUnderY() + TOO_LOW_MARGIN)
                : corridor.ceiling
                    ? corridor.lowerBound
                    : Math.max(corridor.lowerBound, diveFromY - corridor.diveDepth);
            turns.diving(target);
            if (y <= target + (anticipate ? turns.bottomLead() : 0.0)) pitchingDown = false;
        } else if (corridor.ceiling && y >= corridor.upperBound - (anticipate ? turns.topLead() : 0.0)) {
            startDive(y);
            turns.turnedAtCeiling();
        } else if (cycle.toppedOut()) {
            // the climb peaked short of the upper bound (or there's no ceiling): dive to rebuild speed
            startDive(cycle.lastPeakY());
        } else if (pitch <= -MAX_PITCH && BOT.getVelocity().getY() < -FAILED_CLIMB_SINK) {
            // fully pitched up but still sinking: too slow to climb, dive again to build speed
            startDive(y);
        }
        float step = Math.max(0.1f, corridor.rotationSpeed);
        pitch = pitchingDown
            ? Math.min(MAX_PITCH, pitch + step)
            : Math.max(-MAX_PITCH, pitch - step);
    }

    /** At most half the swing, so a bad reading can't collapse it. */
    private static double maxTurnLead() {
        var corridor = CONFIG.corridor;
        return corridor.ceiling
            ? (corridor.upperBound - corridor.lowerBound) / 2.0
            : corridor.diveDepth / 2.0;
    }

    private void startDive(double fromY) {
        pitchingDown = true;
        diveFromY = fromY;
    }

    /**
     * A rocket was just fired or is still boosting us. The chest swap holds off until it's done, so the elytra stays
     * on and the glide isn't restarted mid-boost, which would cut the boost short. Turn off {@code pauseForRockets}
     * to keep swapping through boosts.
     */
    private boolean boostingWithRocket() {
        return CONFIG.chestSwap.pauseForRockets && rockets.inFlight();
    }

    /** Far enough under the corridor that the glide matters more than anything else. */
    private boolean sinking() {
        return BOT.getY() < CONFIG.corridor.lowerBound - EMERGENCY_SAG;
    }

    // ---- rockets ----

    /**
     * Whether to fire a rocket this tick, and why, or null. From most to least urgent:
     * <ul>
     *   <li><b>too low:</b> more than {@link #deepUnderY() halfway} from the lower bound to the auto log height,
     *       or stalled under the band. Fires straight away, no gap, no waiting for a climb.</li>
     *   <li><b>under the band:</b> more than heightTolerance under the lower bound. Fires on a climb, keeping the gap,
     *       whether or not a climb has peaked (a sinking bot's climbs may never peak).</li>
     *   <li><b>the cycle asked for one:</b> a short climb, a boost turn or climb gain. Fires on a climb, keeping the gap.</li>
     * </ul>
     */
    private @Nullable RocketUse rocketWanted() {
        if (CONFIG.rockets.mode == RocketMode.OFF || rocketCooldown > 0) return null;
        boolean urgent = tooLowToWait() || stalledUnderFloor();
        boolean underBand = BOT.getY() < CONFIG.corridor.lowerBound - CONFIG.rockets.heightTolerance;
        if (!cycle.needsRocket && !underBand && !urgent) return null;
        if (rockets.inFlight()) return null;
        if (urgent) return RocketUse.EMERGENCY;

        // normal rockets keep a gap between them
        if (Duration.between(lastRocketAt, Instant.now()).toMillis() < Math.max(0, CONFIG.rockets.minSecondsBetween) * 1000L) {
            return null;
        }
        // spend it on a climb, and only when it actually adds speed along the look direction
        var velocity = BOT.getVelocity();
        boolean climbing = BOT.getPitch() < 0.0f || velocity.getY() > GlideCycle.VELOCITY_DEADBAND;
        if (!climbing) return null;
        var look = MathHelper.calculateViewVector(BOT.getYaw(), BOT.getPitch());
        double speedAlongLook = velocity.getX() * look.getX() + velocity.getY() * look.getY() + velocity.getZ() * look.getZ();
        if (speedAlongLook >= ROCKET_GATE) return null;
        if (!cycle.needsRocket) return RocketUse.CORRIDOR;
        return cycle.rocketForBoost ? RocketUse.BOOST
            : CONFIG.corridor.ceiling ? RocketUse.CORRIDOR
            : RocketUse.CLIMB;
    }

    /**
     * Halfway from the lower bound down to the auto log height (at most {@value #DEEP_UNDER} under): below this the
     * bot stops cycling, points up and rockets without waiting, so it never sinks far enough to auto log.
     */
    private static double deepUnderY() {
        return CONFIG.corridor.lowerBound - Math.min(DEEP_UNDER, Math.max(0.0, CONFIG.autoLog.blocksBelowLowerBound) / 2.0);
    }

    /**
     * Too low to keep cycling, and there are rockets to climb out with. Without rockets, pointing up would only
     * stall it, so it keeps doing Pitch40, the gentlest way down, and auto log catches it at the bottom.
     */
    private boolean tooLowToWait() {
        return BOT.getY() < deepUnderY() && CONFIG.rockets.mode != RocketMode.OFF && rocketCount() > 0;
    }

    /** Warns in Discord once when rockets get low, and once when they run out. Checked once a second. */
    private void checkRocketSupply() {
        if (++rocketCheckTicks < 20) return;
        rocketCheckTicks = 0;
        if (CONFIG.rockets.mode == RocketMode.OFF) return;
        int count = rocketCount();
        if (count == 0) {
            if (outOfRockets.show()) {
                warn("Out of firework rockets");
                discordNotification(Embed.builder()
                    .title("Out of rockets")
                    .description("Pitch40 carries on without them and glides down slowly; auto log disconnects it if it gets too low")
                    .errorColor());
            }
            return;
        }
        outOfRockets.clear();
        if (count <= CONFIG.rockets.lowWarning) {
            if (lowRockets.show()) {
                warn("Only {} firework rockets left", count);
                discordNotification(Embed.builder()
                    .title("Rockets low")
                    .description(count + " rockets left")
                    .addField("Position", Math.round(BOT.getX()) + ", " + Math.round(BOT.getY()) + ", " + Math.round(BOT.getZ()))
                    .primaryColor());
            }
        } else {
            lowRockets.clear();
        }
    }

    /** Where a launch climbs to: the launch height setting, or the upper bound. */
    public double launchTargetY() {
        return Launcher.targetY();
    }

    /**
     * An actual stall under the corridor: pitched up but falling, with almost no forward speed. Just being under
     * the lower bound isn't one; the bot climbs back up on its own. Diving doesn't count either.
     */
    private boolean stalledUnderFloor() {
        var velocity = BOT.getVelocity();
        double horizontalSpeed = Math.hypot(velocity.getX(), velocity.getZ());
        return BOT.getY() < CONFIG.corridor.lowerBound - EMERGENCY_SAG
            && BOT.getPitch() < 0.0f
            && velocity.getY() < -STALL_SINK
            && horizontalSpeed < STALL_HORIZONTAL_SPEED;
    }

    /**
     * Builds the right-click for a rocket if one is wanted and in hand, moving rockets into the hotbar first
     * if needed. Returns null when no click should be made this tick.
     */
    private @Nullable Input rocketClick(boolean wanted) {
        if (!wanted || !rocketMove.isCompleted()) return null;
        var result = doInventoryActionsV2();
        switch (result.state()) {
            case ITEM_IN_HAND -> {
                noRockets.clear();
                return Input.builder()
                    .rightClick(true)
                    .hand(getHand())
                    .clickTarget(ClickTarget.None.INSTANCE)
                    .clickRequiresRotation(false)
                    .build();
            }
            case SWAPPING -> rocketMove = result.inventoryActionFuture();
            case NO_ITEM -> {
                rocketCooldown = NO_ROCKETS_RETRY_TICKS;
                if (noRockets.show()) warn("No firework rockets available");
            }
        }
        return null;
    }

    /** Sends this tick's pitch (and yaw, and rocket click). Returns true if a rocket click was sent. */
    private boolean submitFlightInput(@Nullable RocketUse rocketWanted) {
        Input click = rocketClick(rocketWanted != null);
        var request = InputRequest.builder()
            .owner(this)
            .pitch(pitch)
            .priority(getPriority());
        if (CONFIG.corridor.lockYaw) request.yaw(CONFIG.corridor.yaw);
        if (click != null) request.input(click);
        var future = INPUTS.submit(request.build());
        if (click == null) return false;

        RocketUse use = rocketWanted;
        future.addInputExecutedListener(f -> onRocketClickExecuted(f, use));
        return true;
    }

    /**
     * Sends one tick of launch input for {@link Launcher}.
     *
     * @param climb point up at the launch pitch
     * @param jump  hold jump this tick
     * @param rocket fire a rocket if none is already boosting
     */
    void submitLaunchInput(boolean climb, boolean jump, boolean rocket) {
        Input click = rocket && rocketCooldown == 0 && !rockets.inFlight() ? rocketClick(true) : null;
        Input input = click != null ? click : Input.builder().jumping(jump).build();
        var request = InputRequest.builder()
            .owner(this)
            .input(input)
            .priority(getPriority());
        if (climb) {
            pitch = Launcher.climbPitch();
            request.pitch(pitch);
        }
        if (CONFIG.corridor.lockYaw) request.yaw(CONFIG.corridor.yaw);
        var future = INPUTS.submit(request.build());
        if (click != null) future.addInputExecutedListener(f -> onRocketClickExecuted(f, RocketUse.LAUNCH));
    }

    private void onRocketClickExecuted(InputRequestFuture future, RocketUse use) {
        boolean used = future.getClickResult() instanceof ClickResult.RightClickResult rightClick
            && rightClick.getType() == ClickResult.RightClickResult.RightClickType.USE_ITEM;
        if (!used) {
            rocketCooldown = CLICK_REJECTED_RETRY_TICKS;
            return;
        }
        rockets.fired();
        cycle.needsRocket = false;
        lastRocketAt = Instant.now();
        rocketUses.merge(use, 1, Integer::sum);
        debug("Rocket {} ({}) at Y {}, pitch {}", rockets.firedCount(), use.label, (int) BOT.getY(), (int) BOT.getPitch());
    }

    /** Blocks AutoReconnect after the plugin's disconnects, whether or not Pitch40 is on. Call once at load. */
    public static void registerReconnectGuard() {
        AutoLog.registerReconnectGuard();
    }

    /** Disconnects right away (the illegal way if that's on), putting the elytra back first. */
    public void logoutNow(String reason) {
        restoreElytra();
        AutoLog.disconnectNow(reason);
    }

    /** Puts the elytra back on if the dukway chest swap has the chestplate on. */
    void restoreElytra() {
        chestSwap.restoreElytra();
    }

    /** Start a regear now, or say why it can't. Returns null if it started. */
    public @Nullable String regearNow() {
        if (!Proxy.getInstance().isConnected() || Proxy.getInstance().hasActivePlayer()) {
            return "the bot has to be in control (not connected, or you're logged into the proxy)";
        }
        if (regear.active()) return "already regearing";
        String problem = regear.problem();
        if (problem != null) return problem;
        endFlight();
        launcher.cancel();
        regear.start("asked for");
        return null;
    }

    /** Schedule a launch to the upper bound. Runs once the bot is in control. */
    public void launchNow() {
        launcher.arm(true);
    }

    // ---- status ----

    public int rocketCount() {
        return Items.count(Items::isRocket);
    }

    public int spareElytraCount() {
        return elytra.spareCount();
    }

    /** Worn elytra, then spares grouped by durability, for the {@code pitch40 elytras} command. */
    public List<String> elytraDurabilities() {
        return ElytraManager.durabilityList();
    }

    public int minSpareDurability() {
        return ElytraManager.minSpareDurability();
    }

    public int chestplateHotbarSlot() {
        return chestSwap.chestplateHotbarSlot();
    }

    public int rocketHotbarSlot() {
        return rocketHotbarSlot;
    }

    /** What the bot is doing, or in plain words what it's waiting for. */
    public String status() {
        if (!isEnabled()) return "off";
        if (!Proxy.getInstance().isConnected()) return "not connected to the server";
        if (Proxy.getInstance().hasActivePlayer()) {
            return "waiting: you're logged into the proxy, so you're in control. The bot takes over when you disconnect";
        }
        // the regear drives the pathfinder itself, so check it first
        if (regear.active()) return "regearing: " + regear.describe();
        if (BARITONE.isActive()) return "paused (pathfinder active)";
        if (launcher.active()) return launcher.describe();
        if (!flying) return idleReason();
        var status = new StringBuilder()
            .append(pitchingDown ? "diving" : "climbing")
            .append(" | pitch ").append(Math.round(pitch))
            .append(" | rockets fired ").append(rockets.firedCount());
        if (!rocketUses.isEmpty()) status.append(" (").append(rocketUseText()).append(")");
        if (rockets.pending()) status.append(" (unconfirmed)");
        if (!CONFIG.corridor.ceiling && !Double.isNaN(cycle.bestPeakY())) {
            status.append(" | best peak Y ").append(Math.round(cycle.bestPeakY()));
        }
        if (CONFIG.chestSwap.enabled) {
            status.append(" | chest swaps (").append(CONFIG.chestSwap.mode.name().toLowerCase()).append(") ")
                .append(chestSwap.swapCount());
            if (chestSwap.lateCount() > 0) status.append(", ").append(chestSwap.lateCount()).append(" late");
            if (boostingWithRocket()) status.append(" (paused for rocket)");
        }
        return status.toString();
    }

    /** e.g. "launch 13, holding height 2". */
    public String rocketUseText() {
        if (rocketUses.isEmpty()) return "none yet";
        var parts = new ArrayList<String>();
        rocketUses.forEach((use, count) -> parts.add(use.label + " " + count));
        return String.join(", ", parts);
    }

    private String idleReason() {
        if (!Items.isElytra(Items.chest())) return "waiting: no elytra in the chest slot";
        if (BOT.isOnGround()) {
            return CONFIG.launch.enabled
                ? "on the ground and didn't launch (see the warning in the terminal); try `pitch40 launch now`"
                : "on the ground: start gliding, or turn on `pitch40 launch` so it takes off by itself";
        }
        if (!BOT.isFallFlying()) {
            return CONFIG.elytra.autoDeploy ? "in the air, opening the elytra" : "in the air but not gliding (autoDeploy is off)";
        }
        return "gliding but too low to start: get above Y " + (int) CONFIG.corridor.upperBound + " or carry rockets";
    }

    // ---- reset ----

    /** Ends the current flight but keeps counters and warnings. */
    private void endFlight() {
        flying = false;
        pitchingDown = true;
        pitch = MAX_PITCH;
        cycle.reset();
        rockets.clearPending();
        rocketCooldown = 0;
        jumpHeld = false;
        rocketMove = RequestFuture.rejected;
        chestSwap.resetFlight();
        waitingToStart.clear();
    }

    private void reset() {
        endFlight();
        rockets.reset();
        elytra.reset();
        chestSwap.reset();
        noRockets.clear();
        lowRockets.clear();
        outOfRockets.clear();
        lastDimension = null;
        rocketUses.clear();
        lastRocketAt = Instant.EPOCH;
    }
}
