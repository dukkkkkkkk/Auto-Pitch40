package io.github.dukkkkkkkk.pitch40.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.event.client.ClientBotTick;
import com.zenith.feature.player.ClickResult;
import com.zenith.feature.player.ClickTarget;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.mc.dimension.DimensionData;
import com.zenith.module.impl.AbstractInventoryModule;
import com.zenith.util.RequestFuture;
import com.zenith.util.math.MathHelper;
import io.github.dukkkkkkkk.pitch40.Pitch40Config.RocketMode;
import io.github.dukkkkkkkk.pitch40.util.Items;
import io.github.dukkkkkkkk.pitch40.util.Notice;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.jspecify.annotations.Nullable;

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
 * <p>Only runs while no player is controlling the proxy, and pauses while ZenithProxy's pathfinder is active.
 */
public class Pitch40Module extends AbstractInventoryModule {
    private static final float MAX_PITCH = 40.0f;
    /** A cycle can start this far above the lower bound without rockets, as in Meteor's Pitch40. */
    private static final double START_MARGIN = 40.0;
    private static final int NO_ROCKETS_RETRY_TICKS = 20;
    private static final int CLICK_REJECTED_RETRY_TICKS = 5;
    /** Under the lower bound by this much and stalling: emergency rocket. */
    private static final double EMERGENCY_SAG = 10.0;
    /** Under the lower bound by this much: emergency rocket even at speed. */
    private static final double EMERGENCY_DEEP = 30.0;
    private static final double STALL_SPEED = 0.8;
    /** A climbing rocket is skipped when speed along the look direction is already this high (blocks/tick). */
    private static final double ROCKET_GATE = 0.7;

    private final int rocketHotbarSlot;
    private final GlideCycle cycle = new GlideCycle();
    private final RocketTracker rockets = new RocketTracker();
    private final ElytraManager elytra = new ElytraManager(this);
    private final ChestSwap chestSwap;

    /** A Pitch40 cycle is running. Cleared on landing, dimension change, or disable. */
    private boolean flying = false;
    private boolean pitchingDown = true;
    private float pitch = MAX_PITCH;
    private int rocketCooldown = 0;
    private boolean jumpHeld = false;
    private RequestFuture rocketMove = RequestFuture.rejected;
    private @Nullable DimensionData lastDimension = null;

    private final Notice waitingToStart = new Notice();
    private final Notice noRockets = new Notice();

    public Pitch40Module() {
        super(HandRestriction.EITHER, configuredRocketSlot());
        this.rocketHotbarSlot = configuredRocketSlot();
        this.chestSwap = new ChestSwap(this, rocketHotbarSlot);
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
            of(ClientBotTick.Starting.class, e -> reset()),
            of(ClientBotTick.Stopped.class, e -> reset())
        );
    }

    @Override
    public void onEnable() {
        reset();
    }

    @Override
    public void onDisable() {
        reset();
    }

    // ---- tick ----

    private void onTick(ClientBotTick event) {
        if (!CACHE.getPlayerCache().getThePlayer().isAlive()) {
            endFlight();
            return;
        }
        checkDimension();

        if (rocketCooldown > 0) rocketCooldown--;
        if (rockets.tick() == RocketTracker.Observation.UNCONFIRMED) {
            debug("Rocket not seen attached after {} ticks, allowing another", RocketTracker.CONFIRM_TIMEOUT_TICKS);
        }

        // the pathfinder flies on its own (e.g. `pathfinder elytra`)
        if (BARITONE.isActive()) {
            cycle.reset();
            return;
        }
        if (BOT.isOnGround()) {
            if (flying) info("Landed, Pitch40 stopped");
            endFlight();
            return;
        }
        if (!elytra.tick()) {
            cycle.reset();
            return;
        }
        if (!BOT.isFallFlying()) {
            cycle.reset();
            deployElytra();
            return;
        }
        if (!flying && !tryStartFlight()) return;

        steerPitch();
        cycle.sample(
            BOT.getY(),
            BOT.getVelocity().getY(),
            CONFIG.rockets.mode,
            CONFIG.corridor.upperBound,
            CONFIG.rockets.heightTolerance,
            CONFIG.rockets.climbsPerRocket
        );
        boolean firingRocket = submitFlightInput(wantsRocket());
        chestSwap.tick(!firingRocket && !sinking() && !elytra.busy() && rocketMove.isCompleted());
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
    private void deployElytra() {
        if (!CONFIG.elytra.autoDeploy) return;
        // only while actually falling, so jumping off something still behaves normally
        if (BOT.getVelocity().getY() >= -GlideCycle.VELOCITY_DEADBAND) return;
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
                info("Waiting: glide at or above Y {} to start Pitch40, or carry rockets", (int) CONFIG.corridor.upperBound);
            }
            return false;
        }
        flying = true;
        waitingToStart.clear();
        pitchingDown = BOT.getY() > CONFIG.corridor.lowerBound;
        pitch = MathHelper.clamp(BOT.getPitch(), -MAX_PITCH, MAX_PITCH);
        cycle.reset();
        info("Pitch40 started at Y {} (rockets: {})", (int) BOT.getY(), CONFIG.rockets.mode.name().toLowerCase());
        return true;
    }

    private boolean canStartFlight() {
        var corridor = CONFIG.corridor;
        double y = BOT.getY();
        if (y >= corridor.upperBound && y - START_MARGIN >= corridor.lowerBound) return true;
        // with rockets the corridor can be climbed back into from anywhere
        return CONFIG.rockets.mode != RocketMode.OFF && rocketCount() > 0;
    }

    /** Same corridor logic as Meteor's ElytraFly Pitch40 mode. */
    private void steerPitch() {
        var corridor = CONFIG.corridor;
        double y = BOT.getY();
        if (pitchingDown && y <= corridor.lowerBound) {
            pitchingDown = false;
        } else if (!pitchingDown && y >= corridor.upperBound) {
            pitchingDown = true;
        }
        float step = Math.max(0.1f, corridor.rotationSpeed);
        pitch = pitchingDown
            ? Math.min(MAX_PITCH, pitch + step)
            : Math.max(-MAX_PITCH, pitch - step);
    }

    /** Far enough under the corridor that the glide matters more than anything else. */
    private boolean sinking() {
        return BOT.getY() < CONFIG.corridor.lowerBound - EMERGENCY_SAG;
    }

    // ---- rockets ----

    private boolean wantsRocket() {
        if (CONFIG.rockets.mode == RocketMode.OFF || rocketCooldown > 0) return false;
        double y = BOT.getY();
        double floor = CONFIG.corridor.lowerBound;
        var velocity = BOT.getVelocity();
        boolean emergency = y < floor - EMERGENCY_SAG
            && (velocity.length() < STALL_SPEED || y < floor - EMERGENCY_DEEP);
        if (!cycle.needsRocket && !emergency) return false;
        if (rockets.inFlight()) return false;
        if (emergency) return true;

        // spend it on a climb, and only when it actually adds speed along the look direction
        boolean climbing = BOT.getPitch() < 0.0f || velocity.getY() > GlideCycle.VELOCITY_DEADBAND;
        if (!climbing) return false;
        var look = MathHelper.calculateViewVector(BOT.getYaw(), BOT.getPitch());
        double speedAlongLook = velocity.getX() * look.getX() + velocity.getY() * look.getY() + velocity.getZ() * look.getZ();
        return speedAlongLook < ROCKET_GATE;
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
    private boolean submitFlightInput(boolean rocketWanted) {
        Input click = rocketClick(rocketWanted);
        var request = InputRequest.builder()
            .owner(this)
            .pitch(pitch)
            .priority(getPriority());
        if (CONFIG.corridor.lockYaw) request.yaw(CONFIG.corridor.yaw);
        if (click != null) request.input(click);
        var future = INPUTS.submit(request.build());
        if (click == null) return false;

        String reason = !cycle.needsRocket ? "under the floor"
            : cycle.rocketForBoost ? "for the boost"
            : "to hold the corridor";
        future.addInputExecutedListener(f -> {
            boolean used = f.getClickResult() instanceof ClickResult.RightClickResult rightClick
                && rightClick.getType() == ClickResult.RightClickResult.RightClickType.USE_ITEM;
            if (!used) {
                rocketCooldown = CLICK_REJECTED_RETRY_TICKS;
                return;
            }
            rockets.fired();
            cycle.needsRocket = false;
            debug("Rocket {} {} at Y {}, pitch {}", rockets.firedCount(), reason, (int) BOT.getY(), (int) BOT.getPitch());
        });
        return true;
    }

    // ---- status ----

    public int rocketCount() {
        return Items.count(Items::isRocket);
    }

    public int spareElytraCount() {
        return elytra.spareCount();
    }

    public int chestplateHotbarSlot() {
        return chestSwap.chestplateHotbarSlot();
    }

    public int rocketHotbarSlot() {
        return rocketHotbarSlot;
    }

    public String status() {
        if (!isEnabled()) return "off";
        if (BARITONE.isActive()) return "paused (pathfinder active)";
        if (!flying) return "waiting for a glide";
        var status = new StringBuilder()
            .append(pitchingDown ? "diving" : "climbing")
            .append(" | pitch ").append(Math.round(pitch))
            .append(" | rockets fired ").append(rockets.firedCount());
        if (rockets.pending()) status.append(" (unconfirmed)");
        if (CONFIG.chestSwap.enabled) status.append(" | chest swaps ").append(chestSwap.swapCount());
        return status.toString();
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
        lastDimension = null;
    }
}
