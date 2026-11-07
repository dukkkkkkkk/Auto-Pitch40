package io.github.dukkkkkkkk.pitch40.module;

import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.util.InventoryActionMacros;
import com.zenith.util.ItemUtil;
import com.zenith.util.RequestFuture;
import io.github.dukkkkkkkk.pitch40.util.Items;
import io.github.dukkkkkkkk.pitch40.util.Notice;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;

import static com.zenith.Globals.INVENTORY;
import static io.github.dukkkkkkkk.pitch40.Pitch40Plugin.CONFIG;

/**
 * Watches the worn elytra: replaces it with the next spare when it runs low (most-used first by default), and warns
 * when it can't.
 */
final class ElytraManager {
    /** An elytra can't glide at 1 durability left, so never swap at or below this. */
    private static final int MIN_REPLACE_DURABILITY = 2;

    private final Pitch40Module module;
    private RequestFuture replaceFuture = RequestFuture.rejected;
    private final Notice noElytra = new Notice();
    private final Notice lowDurability = new Notice();
    private final Notice noSpare = new Notice();
    private final Notice noSpareForLastElytra = new Notice();
    /** A replacement was submitted and hasn't been checked for "last elytra" yet. */
    private boolean replacedSinceCheck = false;

    ElytraManager(Pitch40Module module) {
        this.module = module;
    }

    /**
     * Call once per airborne tick. Returns true when a usable elytra is worn.
     *
     * @param allowReplace false while something else (the dukway chest swap) has the chest slot
     */
    boolean tick(boolean allowReplace) {
        if (CONFIG.elytra.autoReplace && allowReplace) tryReplace(Items.chest());

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

    /**
     * True once a replacement this module made has finished and no usable spare is left, i.e. the bot just
     * swapped onto its last elytra, which is still fresh. Only replacements count, so items flicking through
     * the inventory (like the chest swap) can't set it off.
     */
    boolean reachedLastElytra() {
        int threshold = replaceThreshold();
        if (!replacedSinceCheck) {
            if (spareCount() == 0) {
                if (noSpareForLastElytra.show()) {
                    module.info("Last elytra is on, but there's no spare elytra, so it won't disconnect");
                }
            } else {
                noSpareForLastElytra.clear();
            }
            return false;
        }
        // wait for the swap clicks to finish and the inventory to settle
        if (busy() || !Items.inventoryIdle()) return false;
        replacedSinceCheck = false;
        var chest = Items.chest();
        boolean wearingFresh = Items.isElytra(chest) && Items.durabilityLeft(chest) > threshold;
        return wearingFresh && spareCount() == 0;
    }

    private static int replaceThreshold() {
        return Math.max(MIN_REPLACE_DURABILITY, CONFIG.elytra.replaceDurability);
    }

    /**
     * The least durability a spare needs before the bot will swap to it: the {@code minSpareDurability} setting,
     * and always above the replace threshold so it never swaps to one it would immediately replace again.
     */
    static int minSpareDurability() {
        return Math.max(CONFIG.elytra.minSpareDurability, replaceThreshold() + 1);
    }

    static boolean isUsableSpare(@Nullable ItemStack stack) {
        return Items.isElytra(stack) && Items.durabilityLeft(stack) >= minSpareDurability();
    }

    /** The worn elytra is due to be replaced and there's a spare to replace it with. */
    boolean needsReplacement() {
        if (!CONFIG.elytra.autoReplace) return false;
        var chest = Items.chest();
        if (chest != null && !Items.isElytra(chest)) return false;
        boolean due = chest == null || Items.durabilityLeft(chest) <= replaceThreshold();
        return due && bestSpareSlot() != -1;
    }

    /** An elytra replacement is still being clicked through. */
    boolean busy() {
        return !replaceFuture.isCompleted();
    }

    /** Elytras in the inventory (not the worn one) the bot is allowed to swap to. */
    int spareCount() {
        int count = 0;
        for (int slot = Items.MAIN_START; slot <= Items.HOTBAR_END; slot++) {
            if (isUsableSpare(Items.slot(slot))) count++;
        }
        return count;
    }

    void reset() {
        replaceFuture = RequestFuture.rejected;
        replacedSinceCheck = false;
        noSpareForLastElytra.clear();
        noElytra.clear();
        lowDurability.clear();
        noSpare.clear();
    }

    /**
     * Swaps in the next spare (see {@link #bestSpareSlot}) once the worn elytra is down to the replace threshold,
     * or equips one if the chest slot is empty. A chestplate in the chest slot is left alone.
     *
     * <p>Clicks go spare, chest, spare: the spare is picked up, swapped onto the chest, and the old elytra is
     * put down where the spare was. The chest slot is never empty, so the glide isn't interrupted.
     */
    private void tryReplace(@Nullable ItemStack chest) {
        if (busy()) return;
        if (chest != null && !Items.isElytra(chest)) return;
        int threshold = replaceThreshold();
        if (chest != null && Items.durabilityLeft(chest) > threshold) {
            noSpare.clear();
            return;
        }
        if (!Items.inventoryIdle()) return;

        int spareSlot = bestSpareSlot();
        if (spareSlot == -1) {
            if (noSpare.show()) module.warn("Elytra needs replacing but no spare has at least {} durability", minSpareDurability());
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
            replacedSinceCheck = true;
        }
    }

    /**
     * Inventory slot of the next usable spare to wear, or -1. With useWornFirst, the one with the least durability
     * left (still at least minSpareDurability), so part-used elytras get finished off and the fullest are saved
     * for last. Otherwise the one with the most.
     */
    private int bestSpareSlot() {
        boolean wornFirst = CONFIG.elytra.useWornFirst;
        int bestSlot = -1;
        int bestDurability = wornFirst ? Integer.MAX_VALUE : -1;
        for (int slot = Items.MAIN_START; slot <= Items.HOTBAR_END; slot++) {
            var stack = Items.slot(slot);
            if (!isUsableSpare(stack)) continue;
            int durability = Items.durabilityLeft(stack);
            if (wornFirst ? durability < bestDurability : durability > bestDurability) {
                bestDurability = durability;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    /**
     * The worn elytra, then every elytra in the inventory grouped by durability, most first, e.g.
     * "9x full", "4x 300". Spares too low to swap to are marked.
     */
    static List<String> durabilityList() {
        var lines = new ArrayList<String>();
        var chest = Items.chest();
        lines.add(Items.isElytra(chest) ? "Wearing: " + durabilityText(chest) : "Wearing: no elytra");

        // durability left -> how many, highest first
        var groups = new TreeMap<Integer, Integer>(Comparator.reverseOrder());
        var full = new HashMap<Integer, Boolean>();
        for (int slot = Items.MAIN_START; slot <= Items.HOTBAR_END; slot++) {
            var stack = Items.slot(slot);
            if (!Items.isElytra(stack)) continue;
            int durability = Items.durabilityLeft(stack);
            groups.merge(durability, 1, Integer::sum);
            full.put(durability, isFull(stack));
        }
        if (groups.isEmpty()) {
            lines.add("No spares");
            return lines;
        }
        int min = minSpareDurability();
        groups.forEach((durability, count) -> lines.add(count + "x "
            + (full.get(durability) ? "full" : String.valueOf(durability))
            + (durability < min ? " (too low to swap to)" : "")));
        return lines;
    }

    private static boolean isFull(ItemStack stack) {
        return ItemUtil.getDamageValue(stack) == 0;
    }

    private static String durabilityText(ItemStack stack) {
        return isFull(stack) ? "full" : String.valueOf(Items.durabilityLeft(stack));
    }
}
