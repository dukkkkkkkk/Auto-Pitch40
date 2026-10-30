package io.github.dukkkkkkkk.pitch40.module;

import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.MoveToHotbarSlot;
import com.zenith.util.RequestFuture;
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

import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.INVENTORY;
import static io.github.dukkkkkkkk.pitch40.Pitch40Plugin.CONFIG;

/**
 * Keeps elytra durability loss near zero.
 *
 * <p>An elytra only loses durability after 20 unbroken ticks of gliding. Every {@code intervalTicks} this
 * silently swaps a hotbar chestplate onto the chest slot and the elytra straight back (two hotbar-swap clicks,
 * the held item never changes), then resends the glide start, so the server's glide counter never gets that far.
 *
 * <p>The packets are built here instead of going through ZenithProxy's inventory manager, which spaces clicks out
 * over ticks and predicts each from the last server update. All three need to go out in the same tick.
 */
final class ChestSwap {
    private final Pitch40Module module;
    private final int rocketHotbarSlot;
    private int ticksSinceSwap = 0;
    private int swaps = 0;
    private RequestFuture chestplateMove = RequestFuture.rejected;
    private final Notice noChestplate = new Notice();

    ChestSwap(Pitch40Module module, int rocketHotbarSlot) {
        this.module = module;
        this.rocketHotbarSlot = rocketHotbarSlot;
    }

    /**
     * Call once per gliding tick.
     *
     * @param allowed false postpones a due swap to a later tick without resetting the count
     */
    void tick(boolean allowed) {
        if (!CONFIG.chestSwap.enabled) return;
        if (++ticksSinceSwap < Math.max(1, CONFIG.chestSwap.intervalTicks)) return;
        if (!allowed || busy() || INVENTORY.hasActiveRequest() || !Items.inventoryIdle()) return;

        ItemStack elytra = Items.chest();
        if (!Items.isElytra(elytra)) return;

        int hotbarIndex = findHotbarChestplate();
        if (hotbarIndex == -1) {
            moveChestplateToHotbar();
            return;
        }
        noChestplate.clear();

        int hotbarSlot = Items.HOTBAR_START + hotbarIndex;
        ItemStack chestplate = Items.slot(hotbarSlot);
        int stateId = CACHE.getPlayerCache().getInventoryCache().getOpenContainer().getStateId();
        var action = MoveToHotbarAction.from(hotbarIndex);

        // 1. chestplate onto the chest slot, elytra into the hotbar slot
        module.sendClientPacketAsync(swapClick(stateId, action, hotbarSlot, chestplate, elytra));
        // 2. elytra straight back onto the chest slot
        module.sendClientPacketAsync(swapClick(stateId, action, hotbarSlot, elytra, chestplate));
        // 3. glide start
        module.sendClientPacketAsync(new ServerboundPlayerCommandPacket(
            CACHE.getPlayerCache().getEntityId(), PlayerState.START_ELYTRA_FLYING));

        ticksSinceSwap = 0;
        swaps++;
    }

    /** A chestplate is still being moved into the hotbar. */
    boolean busy() {
        return !chestplateMove.isCompleted();
    }

    int swapCount() {
        return swaps;
    }

    /** Clears per-flight state; the swap count is kept. */
    void resetFlight() {
        ticksSinceSwap = 0;
        chestplateMove = RequestFuture.rejected;
    }

    void reset() {
        resetFlight();
        swaps = 0;
        noChestplate.clear();
    }

    /** The configured chestplate hotbar slot, moved off the rocket slot if they clash. */
    int chestplateHotbarSlot() {
        int slot = Math.clamp(CONFIG.chestSwap.hotbarSlot, 0, 8);
        if (slot == rocketHotbarSlot) slot = rocketHotbarSlot == 0 ? 1 : rocketHotbarSlot - 1;
        return slot;
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
