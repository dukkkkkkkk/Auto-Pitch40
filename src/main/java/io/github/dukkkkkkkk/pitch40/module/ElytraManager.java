package io.github.dukkkkkkkk.pitch40.module;

import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.util.InventoryActionMacros;
import com.zenith.util.RequestFuture;
import io.github.dukkkkkkkk.pitch40.util.Items;
import io.github.dukkkkkkkk.pitch40.util.Notice;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.jspecify.annotations.Nullable;

import static com.zenith.Globals.INVENTORY;
import static io.github.dukkkkkkkk.pitch40.Pitch40Plugin.CONFIG;

/**
 * Watches the worn elytra: replaces it with the best spare when it runs low, and warns when it can't.
 */
final class ElytraManager {
    /** An elytra can't glide at 1 durability left, so never swap at or below this. */
    private static final int MIN_REPLACE_DURABILITY = 2;

    private final Pitch40Module module;
    private RequestFuture replaceFuture = RequestFuture.rejected;
    private final Notice noElytra = new Notice();
    private final Notice lowDurability = new Notice();
    private final Notice noSpare = new Notice();

    ElytraManager(Pitch40Module module) {
        this.module = module;
    }

    /** Call once per airborne tick. Returns true when a usable elytra is worn. */
    boolean tick() {
        if (CONFIG.elytra.autoReplace) tryReplace(Items.chest());

        ItemStack chest = Items.chest();
        if (!Items.isElytra(chest)) {
            if (noElytra.show()) module.warn("No elytra in the chest slot");
            return false;
        }
        noElytra.clear();

        int durability = Items.durabilityLeft(chest);
        boolean spareWillBeUsed = CONFIG.elytra.autoReplace && spareCount() > 0;
        if (durability <= CONFIG.elytra.lowDurabilityWarning && !spareWillBeUsed) {
            if (lowDurability.show()) module.warn("Elytra durability low: {} left", durability);
        } else {
            lowDurability.clear();
        }
        return durability > 1;
    }

    /** An elytra replacement is still being clicked through. */
    boolean busy() {
        return !replaceFuture.isCompleted();
    }

    int spareCount() {
        int count = 0;
        for (int slot = Items.MAIN_START; slot <= Items.HOTBAR_END; slot++) {
            var stack = Items.slot(slot);
            if (Items.isElytra(stack) && Items.durabilityLeft(stack) > 1) count++;
        }
        return count;
    }

    void reset() {
        replaceFuture = RequestFuture.rejected;
        noElytra.clear();
        lowDurability.clear();
        noSpare.clear();
    }

    /**
     * Swaps in the spare with the most durability once the worn elytra is down to the replace threshold,
     * or equips one if the chest slot is empty. A chestplate in the chest slot is left alone.
     *
     * <p>Clicks go spare, chest, spare: the spare is picked up, swapped onto the chest, and the old elytra is
     * put down where the spare was. The chest slot is never empty, so the glide isn't interrupted.
     */
    private void tryReplace(@Nullable ItemStack chest) {
        if (busy()) return;
        if (chest != null && !Items.isElytra(chest)) return;
        int threshold = Math.max(MIN_REPLACE_DURABILITY, CONFIG.elytra.replaceDurability);
        if (chest != null && Items.durabilityLeft(chest) > threshold) {
            noSpare.clear();
            return;
        }
        if (!Items.inventoryIdle()) return;

        int spareSlot = bestSpareSlot(threshold);
        if (spareSlot == -1) {
            if (noSpare.show()) module.warn("Elytra needs replacing but no spare has more than {} durability", threshold);
            return;
        }
        int spareDurability = Items.durabilityLeft(Items.slot(spareSlot));
        replaceFuture = INVENTORY.submit(InventoryActionRequest.builder()
            .owner(module)
            .priority(module.getPriority())
            .actions(InventoryActionMacros.swapSlots(0, spareSlot, Items.CHEST_SLOT))
            .build());
        // still pending means it was queued; completed but not accepted means another module outranked us
        if (!replaceFuture.isCompleted() || replaceFuture.isAccepted()) {
            module.info("Replacing elytra with a spare ({} durability)", spareDurability);
        }
    }

    /** Inventory slot of the spare with the most durability above the threshold, or -1. */
    private int bestSpareSlot(int threshold) {
        int bestSlot = -1;
        int bestDurability = threshold;
        for (int slot = Items.MAIN_START; slot <= Items.HOTBAR_END; slot++) {
            var stack = Items.slot(slot);
            if (!Items.isElytra(stack)) continue;
            int durability = Items.durabilityLeft(stack);
            if (durability > bestDurability) {
                bestDurability = durability;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }
}
