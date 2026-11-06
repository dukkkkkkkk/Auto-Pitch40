package io.github.dukkkkkkkk.pitch40.module;

import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.inventory.actions.InventoryAction;
import com.zenith.feature.inventory.actions.MoveToHotbarSlot;
import com.zenith.feature.inventory.actions.SetHeldItem;
import com.zenith.feature.inventory.actions.ShiftClick;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.player.ClickTarget;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.feature.player.World;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.module.impl.AutoMend;
import com.zenith.util.RequestFuture;
import com.zenith.util.math.MathHelper;
import io.github.dukkkkkkkk.pitch40.util.Items;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.zenith.Globals.*;
import static io.github.dukkkkkkkk.pitch40.Pitch40Plugin.CONFIG;

/**
 * Lands, places a shulker of XP bottles from the inventory, takes XP out (putting spare rockets in to make room),
 * throws the XP to mend the elytras, mines the shulker back up and takes off again.
 *
 * <p>Starts when the bot gets down to its second-to-last good elytra, so the last one is never needed for it.
 * Landing uses ZenithProxy's elytra pathfinder, which searches for a safe landing spot itself. Every step has a
 * timeout; if one fails, it skips ahead to picking the shulker back up (if it was placed) and taking off.
 */
final class Regear {
    private static final Duration RETRY_AFTER = Duration.ofMinutes(10);
    /** How far ahead along the heading to aim the landing; the pathfinder lands near the end of its path. */
    private static final int LANDING_DISTANCE = 150;
    private static final int LANDING_TIMEOUT_TICKS = 20 * 180;
    private static final int STEP_TIMEOUT_TICKS = 20 * 30;
    private static final int MEND_TIMEOUT_TICKS = 20 * 300;
    /** Ticks between XP bottle throws. */
    private static final int THROW_INTERVAL = 3;
    /** After the last throw, wait this long for the orbs to land and mend. */
    private static final int ORB_SETTLE_TICKS = 40;
    private static final int SHULKER_SLOTS = 27;
    /** In an open shulker, player inventory slot N (9-44) is container slot N + this. */
    private static final int PLAYER_SLOT_OFFSET = 18;

    private enum Phase {
        IDLE("idle"),
        LANDING("landing to regear"),
        PLACING("placing the XP shulker"),
        OPENING("opening the XP shulker"),
        TRANSFERRING("swapping rockets for XP"),
        MENDING("mending elytras"),
        BREAKING("mining the shulker"),
        PICKING_UP("picking the shulker up");

        final String label;

        Phase(String label) {
            this.label = label;
        }
    }

    private final Pitch40Module module;
    private final int rocketHotbarSlot;
    private Phase phase = Phase.IDLE;
    private int ticksInPhase = 0;
    private Instant lastAttempt = Instant.EPOCH;
    private PathingRequestFuture path = PathingRequestFuture.rejected;
    private RequestFuture inventory = RequestFuture.rejected;
    private @Nullable ItemData shulkerItem = null;
    private int shulkerX, shulkerY, shulkerZ;
    private boolean placed = false;
    private int shulkersBeforeBreak = 0;
    /** We switched AutoMend on for mending, so switch it back off after. */
    private boolean turnedOnAutoMend = false;
    private int settleTicks = 0;

    Regear(Pitch40Module module, int rocketHotbarSlot) {
        this.module = module;
        this.rocketHotbarSlot = rocketHotbarSlot;
    }

    boolean active() {
        return phase != Phase.IDLE;
    }

    String describe() {
        return phase.label;
    }

    /**
     * Time to regear: down to the second-to-last good elytra, something to mend, an XP shulker on hand, and not
     * tried in the last few minutes.
     */
    boolean shouldStart(int usableSpares) {
        if (!CONFIG.regear.enabled || active()) return false;
        if (usableSpares > 1) return false;
        if (Duration.between(lastAttempt, Instant.now()).compareTo(RETRY_AFTER) < 0) return false;
        if (!anythingToMend()) return false;
        return problem() == null;
    }

    /** Why a regear can't happen right now, or null if it can. */
    @Nullable String problem() {
        int xpShulkerSlot = Items.find(stack -> Items.xpInShulker(stack) > 0);
        if (xpShulkerSlot == -1) return "no shulker with XP bottles in the inventory";
        int shulkerId = Items.slot(xpShulkerSlot).getId();
        // placing picks the first shulker of that colour, which has to be the XP one
        int sameColour = 0;
        for (int slot = Items.MAIN_START; slot <= Items.OFFHAND_SLOT; slot++) {
            var stack = Items.slot(slot);
            if (stack != null && stack.getId() == shulkerId) sameColour++;
        }
        if (sameColour > 1) return "more than one shulker of the XP shulker's colour, so it might place the wrong one";
        return null;
    }

    /** Begins the regear. The caller has already ended normal flight. */
    void start(String why) {
        lastAttempt = Instant.now();
        var xpShulker = Items.slot(Items.find(stack -> Items.xpInShulker(stack) > 0));
        shulkerItem = ItemRegistry.REGISTRY.get(xpShulker.getId());
        placed = false;
        module.info("Regearing ({}): landing to mend the elytras with {} XP bottles", why, Items.xpInShulker(xpShulker));
        // aim ahead along the current heading; the pathfinder finds a safe spot near the end and lands
        double yaw = Math.toRadians(BOT.getYaw());
        int goalX = MathHelper.floorI(BOT.getX() - Math.sin(yaw) * LANDING_DISTANCE);
        int goalZ = MathHelper.floorI(BOT.getZ() + Math.cos(yaw) * LANDING_DISTANCE);
        path = BARITONE.elytraTo(goalX, goalZ);
        setPhase(Phase.LANDING);
    }

    /** Stops everything (e.g. a player took over), leaving AutoMend how it was. */
    void cancel() {
        if (!active()) return;
        if (BARITONE.isActive()) BARITONE.stop();
        restoreAutoMend();
        phase = Phase.IDLE;
    }

    /**
     * Call every bot tick while {@link #active()}.
     *
     * @return true while still regearing; false once done, after which the caller should take off
     */
    boolean tick() {
        ticksInPhase++;
        switch (phase) {
            case LANDING -> tickLanding();
            case PLACING -> tickPlacing();
            case OPENING -> tickOpening();
            case TRANSFERRING -> tickTransferring();
            case MENDING -> tickMending();
            case BREAKING -> tickBreaking();
            case PICKING_UP -> tickPickingUp();
            case IDLE -> { }
        }
        return active();
    }

    // ---- steps ----

    private void tickLanding() {
        if (BOT.isOnGround() && !BARITONE.isActive()) {
            module.info("Landed at {}, {}, {}", Math.round(BOT.getX()), Math.round(BOT.getY()), Math.round(BOT.getZ()));
            setPhase(Phase.PLACING);
            return;
        }
        if (ticksInPhase > LANDING_TIMEOUT_TICKS || (path.isCompleted() && !BARITONE.isActive() && !BOT.isOnGround())) {
            module.warn("Regear: couldn't find a safe place to land, carrying on and trying again in {} minutes", RETRY_AFTER.toMinutes());
            if (BARITONE.isActive()) BARITONE.stop();
            phase = Phase.IDLE;
        }
    }

    private void tickPlacing() {
        if (ticksInPhase == 1) {
            if (!pickShulkerSpot()) {
                module.warn("Regear: no room next to the bot to place the shulker (needs solid ground and air above)");
                finish();
                return;
            }
            path = BARITONE.placeBlock(shulkerX, shulkerY, shulkerZ, shulkerItem);
            return;
        }
        if (isShulkerBlock(shulkerX, shulkerY, shulkerZ)) {
            placed = true;
            setPhase(Phase.OPENING);
        } else if (ticksInPhase > STEP_TIMEOUT_TICKS || (path.isCompleted() && !BARITONE.isActive() && ticksInPhase > 20)) {
            module.warn("Regear: couldn't place the shulker");
            finish();
        }
    }

    private void tickOpening() {
        if (ticksInPhase == 1) {
            path = BARITONE.rightClickBlock(shulkerX, shulkerY, shulkerZ);
            return;
        }
        if (shulkerOpen()) {
            setPhase(Phase.TRANSFERRING);
        } else if (ticksInPhase > STEP_TIMEOUT_TICKS) {
            module.warn("Regear: the shulker didn't open");
            setPhase(Phase.BREAKING);
        }
    }

    private void tickTransferring() {
        if (ticksInPhase == 1) {
            if (!shulkerOpen()) {
                setPhase(Phase.OPENING);
                return;
            }
            inventory = INVENTORY.submit(InventoryActionRequest.builder()
                .owner(module)
                .priority(module.getPriority())
                .actions(transferActions())
                .build());
            return;
        }
        if (!inventory.isCompleted() && ticksInPhase < STEP_TIMEOUT_TICKS) return;
        int xp = Items.count(Items::isXpBottle);
        if (xp == 0) {
            module.warn("Regear: got no XP bottles out of the shulker");
            setPhase(Phase.BREAKING);
            return;
        }
        module.info("Took {} XP bottles, mending", xp);
        var autoMend = MODULE.get(AutoMend.class);
        // AutoMend keeps a damaged Mending item in the offhand, so the spares get mended too, not just the worn one
        if (!autoMend.isEnabled()) {
            autoMend.enable();
            turnedOnAutoMend = true;
        }
        settleTicks = 0;
        setPhase(Phase.MENDING);
    }

    private void tickMending() {
        boolean done = !anythingToMend() || Items.count(Items::isXpBottle) == 0;
        if (done || ticksInPhase > MEND_TIMEOUT_TICKS) {
            // let the last orbs land before moving on
            if (++settleTicks < ORB_SETTLE_TICKS) return;
            restoreAutoMend();
            // AutoMend may have left an elytra in the offhand, where it doesn't count as a spare
            if (Items.isElytra(Items.slot(Items.OFFHAND_SLOT))) {
                INVENTORY.submit(InventoryActionRequest.builder()
                    .owner(module)
                    .priority(module.getPriority())
                    .actions(new ShiftClick(Items.OFFHAND_SLOT, ShiftClickItemAction.LEFT_CLICK))
                    .build());
            }
            module.info(anythingToMend() ? "Mending stopped with some elytras still damaged" : "All elytras mended");
            setPhase(Phase.BREAKING);
            return;
        }
        if (ticksInPhase % THROW_INTERVAL != 0) return;
        if (!holdXpInMainHand()) return;
        // straight down so the orbs land on the bot
        INPUTS.submit(InputRequest.builder()
            .owner(module)
            .pitch(90.0f)
            .input(Input.builder()
                .rightClick(true)
                .hand(Hand.MAIN_HAND)
                .clickTarget(ClickTarget.None.INSTANCE)
                .clickRequiresRotation(false)
                .build())
            .priority(module.getPriority())
            .build());
    }

    private void tickBreaking() {
        if (!placed) {
            finish();
            return;
        }
        if (ticksInPhase == 1) {
            // the shulker drops itself with whatever's inside, whatever it's mined with; a pickaxe is just faster
            shulkersBeforeBreak = Items.count(Items::isShulker);
            path = BARITONE.breakBlock(shulkerX, shulkerY, shulkerZ, true);
            return;
        }
        if (!isShulkerBlock(shulkerX, shulkerY, shulkerZ)) {
            setPhase(Phase.PICKING_UP);
        } else if (ticksInPhase > STEP_TIMEOUT_TICKS) {
            module.warn("Regear: couldn't mine the shulker back up, leaving it at {}, {}, {}", shulkerX, shulkerY, shulkerZ);
            finish();
        }
    }

    private void tickPickingUp() {
        if (ticksInPhase == 1) {
            path = BARITONE.pickup(shulkerItem);
            return;
        }
        if (Items.count(Items::isShulker) > shulkersBeforeBreak) {
            module.info("Picked the shulker back up");
            if (BARITONE.isActive()) BARITONE.stop();
            finish();
        } else if (ticksInPhase > STEP_TIMEOUT_TICKS) {
            module.warn("Regear: couldn't pick the shulker back up, it's near {}, {}, {}", shulkerX, shulkerY, shulkerZ);
            if (BARITONE.isActive()) BARITONE.stop();
            finish();
        }
    }

    private void finish() {
        restoreAutoMend();
        phase = Phase.IDLE;
        module.info("Regear done, taking off");
    }

    private void setPhase(Phase next) {
        phase = next;
        ticksInPhase = 0;
    }

    // ---- helpers ----

    /** A spot next to the bot with solid ground under it and air in it and above it (a shulker opens upward). */
    private boolean pickShulkerSpot() {
        int x = MathHelper.floorI(BOT.getX());
        int y = MathHelper.floorI(BOT.getY());
        int z = MathHelper.floorI(BOT.getZ());
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (var side : sides) {
            int sx = x + side[0], sz = z + side[1];
            if (!World.isChunkLoadedBlockPos(sx, sz)) continue;
            if (World.getBlock(sx, y - 1, sz).solidBlock()
                && World.getBlock(sx, y, sz).isAir()
                && World.getBlock(sx, y + 1, sz).isAir()) {
                shulkerX = sx;
                shulkerY = y;
                shulkerZ = sz;
                return true;
            }
        }
        return false;
    }

    private static boolean isShulkerBlock(int x, int y, int z) {
        return World.getBlock(x, y, z).name().endsWith("shulker_box");
    }

    /** A shulker's window is open: 27 shulker slots plus the 36 player slots. */
    private static boolean shulkerOpen() {
        var open = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        return open.getContainerId() != 0 && open.getSize() == SHULKER_SLOTS + 36;
    }

    /**
     * Shift-clicks for the open shulker: spare rockets in (keeping keepRockets), then as many XP stacks out as
     * there's room for, then close it.
     */
    private List<InventoryAction> transferActions() {
        var open = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        int containerId = open.getContainerId();
        var actions = new ArrayList<InventoryAction>();

        int freeShulkerSlots = 0;
        for (int slot = 0; slot < SHULKER_SLOTS; slot++) {
            var stack = open.getItemStack(slot);
            if (stack == null) freeShulkerSlots++;
        }
        int freePlayerSlots = 0;
        for (int slot = Items.MAIN_START; slot <= Items.HOTBAR_END; slot++) {
            if (Items.slot(slot) == null) freePlayerSlots++;
        }

        // spare rockets in: keep the first keepRockets, starting with the hotbar rocket slot
        int keep = Math.max(0, CONFIG.regear.keepRockets);
        int kept = 0;
        var rocketSlots = new ArrayList<Integer>();
        rocketSlots.add(Items.HOTBAR_START + rocketHotbarSlot);
        for (int slot = Items.MAIN_START; slot <= Items.HOTBAR_END; slot++) {
            if (slot != Items.HOTBAR_START + rocketHotbarSlot) rocketSlots.add(slot);
        }
        for (int slot : rocketSlots) {
            var stack = Items.slot(slot);
            if (!Items.isRocket(stack)) continue;
            if (kept < keep) {
                kept += stack.getAmount();
                continue;
            }
            if (freeShulkerSlots == 0) break;
            actions.add(new ShiftClick(containerId, slot + PLAYER_SLOT_OFFSET, ShiftClickItemAction.LEFT_CLICK));
            freeShulkerSlots--;
            freePlayerSlots++;
        }

        // XP out, as many stacks as there's room for
        for (int slot = 0; slot < SHULKER_SLOTS && freePlayerSlots > 0; slot++) {
            if (!Items.isXpBottle(open.getItemStack(slot))) continue;
            actions.add(new ShiftClick(containerId, slot, ShiftClickItemAction.LEFT_CLICK));
            freePlayerSlots--;
        }
        actions.add(new CloseContainer());
        return actions;
    }

    /** XP bottles in the main hand, moving them into the hotbar first if needed. Returns true once held. */
    private boolean holdXpInMainHand() {
        int held = CACHE.getPlayerCache().getHeldItemSlot();
        if (Items.isXpBottle(Items.slot(Items.HOTBAR_START + held))) return true;
        if (!inventory.isCompleted()) return false;
        int xpSlot = Items.find(Items::isXpBottle);
        if (xpSlot == -1) return false;
        List<InventoryAction> actions;
        if (xpSlot >= Items.HOTBAR_START && xpSlot <= Items.HOTBAR_END) {
            actions = List.of(new SetHeldItem(xpSlot - Items.HOTBAR_START));
        } else {
            actions = List.of(
                new MoveToHotbarSlot(xpSlot, MoveToHotbarAction.from(rocketHotbarSlot)),
                new SetHeldItem(rocketHotbarSlot));
        }
        inventory = INVENTORY.submit(InventoryActionRequest.builder()
            .owner(module)
            .priority(module.getPriority())
            .actions(actions)
            .build());
        return false;
    }

    /** Any elytra with Mending that has damage: worn, in the inventory or in the offhand. */
    private static boolean anythingToMend() {
        if (Items.isElytra(Items.chest()) && Items.isMendable(Items.chest())) return true;
        return Items.find(stack -> Items.isElytra(stack) && Items.isMendable(stack)) != -1;
    }

    /** Switches AutoMend back off if we were the ones who turned it on. */
    private void restoreAutoMend() {
        if (!turnedOnAutoMend) return;
        turnedOnAutoMend = false;
        var autoMend = MODULE.get(AutoMend.class);
        if (autoMend.isEnabled()) autoMend.disable();
    }
}
