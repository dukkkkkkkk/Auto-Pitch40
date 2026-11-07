package io.github.dukkkkkkkk.pitch40.module;

import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.MoveToHotbarSlot;
import com.zenith.util.RequestFuture;
import io.github.dukkkkkkkk.pitch40.Pitch40Config.ChestSwapMode;
import io.github.dukkkkkkkk.pitch40.util.Items;
import io.github.dukkkkkkkk.pitch40.util.Notice;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerState;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerCommandPacket;
import org.jspecify.annotations.Nullable;

import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.INVENTORY;
import static io.github.dukkkkkkkk.pitch40.Pitch40Plugin.CONFIG;

/**
 * Keeps elytra durability loss near zero. An elytra only loses durability after 20 unbroken ticks of gliding.
 *
 * <p>Two modes, both using silent hotbar-swap clicks on the chest slot (the held item never changes):
 * <ul>
 *   <li><b>quick:</b> every {@code intervalTicks}, in one tick, chestplate on, elytra straight back, then a glide
 *       start. The elytra is worn the whole time; the restart resets the server's glide count.</li>
 *   <li><b>dukway:</b> the chestplate is worn for {@code intervalTicks}, then the elytra for 1 tick with a glide
 *       start, over and over. The elytra is barely ever on the chest slot.</li>
 * </ul>
 *
 * <p>The packets are built here instead of going through ZenithProxy's inventory manager, so they go out exactly
 * when needed, and they don't change ZenithProxy's copy of the inventory: as far as the bot's own simulation knows,
 * the elytra stays on. In dukway, whenever anything else needs the inventory or the glide (a rocket, an elytra
 * replacement, landing, disconnecting, a player taking over), the elytra is put back first.
 */
final class ChestSwap {
    private final Pitch40Module module;
    private final int rocketHotbarSlot;
    /** An elytra loses durability after this many unbroken ticks of gliding. */
    private static final int DURABILITY_TICKS = 20;
    /** A due swap that's still waiting at this many ticks goes ahead even if other inventory clicks are running. */
    private static final int FORCE_AT = 16;

    private int ticksSinceSwap = 0;
    private boolean otherInventoryBusy = false;
    private int swaps = 0;
    private RequestFuture chestplateMove = RequestFuture.rejected;
    private final Notice noChestplate = new Notice();

    /** Swaps that came 20+ ticks after the last one, so the elytra probably lost a point of durability. */
    private int lateSwaps = 0;

    /** Dukway: the chestplate is on the chest slot (server side) and the elytra is in this hotbar slot. */
    private boolean holdingChestplate = false;
    private int heldHotbarIndex = -1;
    /** Dukway: the stacks as they were when swapped, for the swap back (ZenithProxy's copy may have been resynced). */
    private @Nullable ItemStack heldElytra = null;
    private @Nullable ItemStack heldChestplate = null;

    ChestSwap(Pitch40Module module, int rocketHotbarSlot) {
        this.module = module;
        this.rocketHotbarSlot = rocketHotbarSlot;
    }

    static boolean dukwayOn() {
        return CONFIG.chestSwap.enabled && CONFIG.chestSwap.mode == ChestSwapMode.DUKWAY;
    }

    /**
     * Call once per gliding tick.
     *
     * @param allowed            false holds off: quick postpones a due swap; dukway puts the elytra back on and waits
     * @param otherInventoryBusy something else (like a rocket move) is mid-click; waited for, unless a swap is
     *                           about to be too late to save durability
     */
    void tick(boolean allowed, boolean otherInventoryBusy) {
        this.otherInventoryBusy = otherInventoryBusy;
        if (!CONFIG.chestSwap.enabled || CONFIG.chestSwap.mode != ChestSwapMode.DUKWAY) {
            // switched away from dukway mid-swap
            restoreElytra();
        }
        if (!CONFIG.chestSwap.enabled) return;
        if (CONFIG.chestSwap.mode == ChestSwapMode.DUKWAY) {
            tickDukway(allowed);
        } else {
            tickQuick(allowed);
        }
    }

    private void tickQuick(boolean allowed) {
        if (++ticksSinceSwap < Math.max(1, CONFIG.chestSwap.intervalTicks)) return;
        if (!allowed || !readyToClick()) return;
        if (ticksSinceSwap >= DURABILITY_TICKS) lateSwaps++;

        ItemStack elytra = Items.chest();
        if (!Items.isElytra(elytra)) return;
        int hotbarIndex = hotbarChestplateOrMove();
        if (hotbarIndex == -1) return;

        int hotbarSlot = Items.HOTBAR_START + hotbarIndex;
        ItemStack chestplate = Items.slot(hotbarSlot);
        int stateId = stateId();
        var action = MoveToHotbarAction.from(hotbarIndex);

        // 1. chestplate onto the chest slot, elytra into the hotbar slot
        module.sendClientPacketAsync(swapClick(stateId, action, hotbarSlot, chestplate, elytra));
        // 2. elytra straight back onto the chest slot
        module.sendClientPacketAsync(swapClick(stateId, action, hotbarSlot, elytra, chestplate));
        // 3. glide start
        sendGlideStart();

        ticksSinceSwap = 0;
        swaps++;
    }

    /** Chestplate for intervalTicks, then elytra for 1 tick with a glide start. */
    private void tickDukway(boolean allowed) {
        if (!allowed) {
            restoreElytra();
            return;
        }
        if (holdingChestplate) {
            if (++ticksSinceSwap < Math.max(1, CONFIG.chestSwap.intervalTicks)) return;
            // the elytra's 1 tick
            restoreElytra();
            return;
        }
        ticksSinceSwap++;
        if (!readyToClick()) return;
        ItemStack elytra = Items.chest();
        if (!Items.isElytra(elytra)) return;
        int hotbarIndex = hotbarChestplateOrMove();
        if (hotbarIndex == -1) return;

        int hotbarSlot = Items.HOTBAR_START + hotbarIndex;
        ItemStack chestplate = Items.slot(hotbarSlot);
        module.sendClientPacketAsync(swapClick(stateId(), MoveToHotbarAction.from(hotbarIndex), hotbarSlot, chestplate, elytra));
        holdingChestplate = true;
        heldHotbarIndex = hotbarIndex;
        heldElytra = elytra;
        heldChestplate = chestplate;
        ticksSinceSwap = 0;
        swaps++;
    }

    /**
     * Dukway: puts the elytra back on the chest slot and sends a glide start. Does nothing if the elytra is
     * already on. Safe to call any time, including right before disconnecting.
     */
    void restoreElytra() {
        if (!holdingChestplate) return;
        holdingChestplate = false;
        int hotbarSlot = Items.HOTBAR_START + heldHotbarIndex;
        // the stacks as they were when swapped: ZenithProxy's copy normally still shows them that way, but a server
        // resync in between can show them swapped, which would make the predicted slots wrong
        ItemStack elytra = heldElytra != null ? heldElytra : Items.chest();
        ItemStack chestplate = heldChestplate != null ? heldChestplate : Items.slot(hotbarSlot);
        heldElytra = null;
        heldChestplate = null;
        module.sendClientPacketAsync(swapClick(stateId(), MoveToHotbarAction.from(heldHotbarIndex), hotbarSlot, elytra, chestplate));
        sendGlideStart();
        ticksSinceSwap = 0;
    }

    /** Dukway: the chestplate is on right now, so the bot isn't gliding as far as the server's concerned. */
    boolean holdingChestplate() {
        return holdingChestplate;
    }

    /** A chestplate is still being moved into the hotbar. */
    boolean busy() {
        return !chestplateMove.isCompleted();
    }

    int swapCount() {
        return swaps;
    }

    int lateCount() {
        return lateSwaps;
    }

    /** Clears per-flight state (putting the elytra back first); the swap count is kept. */
    void resetFlight() {
        restoreElytra();
        ticksSinceSwap = 0;
        chestplateMove = RequestFuture.rejected;
    }

    void reset() {
        resetFlight();
        swaps = 0;
        lateSwaps = 0;
        noChestplate.clear();
    }

    /** The configured chestplate hotbar slot, moved off the rocket slot if they clash. */
    int chestplateHotbarSlot() {
        int slot = Math.clamp(CONFIG.chestSwap.hotbarSlot, 0, 8);
        if (slot == rocketHotbarSlot) slot = rocketHotbarSlot == 0 ? 1 : rocketHotbarSlot - 1;
        return slot;
    }

    /**
     * Normally waits for any other inventory clicks to finish. When the swap is close to too late to save durability
     * (FORCE_AT ticks), it goes ahead anyway; only an open container or an item on the cursor still stops it.
     */
    private boolean readyToClick() {
        if (!Items.inventoryIdle()) return false;
        if (ticksSinceSwap >= FORCE_AT) return true;
        return !busy() && !otherInventoryBusy && !INVENTORY.hasActiveRequest();
    }

    private static int stateId() {
        return CACHE.getPlayerCache().getInventoryCache().getOpenContainer().getStateId();
    }

    private void sendGlideStart() {
        module.sendClientPacketAsync(new ServerboundPlayerCommandPacket(
            CACHE.getPlayerCache().getEntityId(), PlayerState.START_ELYTRA_FLYING));
    }

    /**
     * Click on the chest slot with a hotbar number key: swaps the chest slot with that hotbar slot.
     * The changed slots are the predicted result, so the server has nothing to resync.
     */
    private static ServerboundContainerClickPacket swapClick(int stateId, MoveToHotbarAction action, int hotbarSlot,
                                                            ItemStack newChest, ItemStack newHotbar) {
        Int2ObjectMap<ItemStack> changedSlots = new Int2ObjectArrayMap<>();
        changedSlots.put(hotbarSlot, newHotbar);
        changedSlots.put(Items.CHEST_SLOT, newChest);
        return new ServerboundContainerClickPacket(
            0,
            stateId,
            Items.CHEST_SLOT,
            ContainerActionType.MOVE_TO_HOTBAR_SLOT,
            action,
            null,
            changedSlots
        );
    }

    /** Hotbar index of a chestplate, or -1 after starting to move one there (or warning there's none). */
    private int hotbarChestplateOrMove() {
        int hotbarIndex = findHotbarChestplate();
        if (hotbarIndex == -1) {
            moveChestplateToHotbar();
            return -1;
        }
        noChestplate.clear();
        return hotbarIndex;
    }

    /** Hotbar index (0-8) holding a chestplate, or -1. The rocket slot is skipped so the two never fight. */
    private int findHotbarChestplate() {
        int preferred = chestplateHotbarSlot();
        if (Items.isChestplate(Items.slot(Items.HOTBAR_START + preferred))) return preferred;
        for (int i = 0; i < 9; i++) {
            if (i == rocketHotbarSlot) continue;
            if (Items.isChestplate(Items.slot(Items.HOTBAR_START + i))) return i;
        }
        return -1;
    }

    private void moveChestplateToHotbar() {
        for (int slot = Items.MAIN_START; slot < Items.HOTBAR_START; slot++) {
            if (!Items.isChestplate(Items.slot(slot))) continue;
            chestplateMove = INVENTORY.submit(InventoryActionRequest.builder()
                .owner(module)
                .priority(module.getPriority())
                .actions(new MoveToHotbarSlot(slot, MoveToHotbarAction.from(chestplateHotbarSlot())))
                .build());
            return;
        }
        if (noChestplate.show()) module.warn("Chest swap is on but there is no chestplate in the inventory");
    }
}
