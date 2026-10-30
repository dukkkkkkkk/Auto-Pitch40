package io.github.dukkkkkkkk.pitch40.util;

import com.zenith.feature.pathfinder.process.elytra.ElytraBehavior;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.util.ItemUtil;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

import static com.zenith.Globals.CACHE;

/**
 * Item predicates and player inventory lookups.
 *
 * <p>Slot numbers are player inventory container slots:
 * 5-8 armor, 9-35 main inventory, 36-44 hotbar, 45 offhand.
 */
public final class Items {
    public static final int CHEST_SLOT = 6;
    public static final int MAIN_START = 9;
    public static final int HOTBAR_START = 36;
    public static final int HOTBAR_END = 44;
    public static final int OFFHAND_SLOT = 45;

    private Items() {}

    public static boolean isElytra(@Nullable ItemStack stack) {
        return stack != null && stack.getId() == ItemRegistry.ELYTRA.id();
    }

    /** A plain flight rocket. Rockets with explosions are excluded. */
    public static boolean isRocket(@Nullable ItemStack stack) {
        return ElytraBehavior.isFireworks(stack);
    }

    public static boolean isChestplate(@Nullable ItemStack stack) {
        if (stack == null) return false;
        var data = ItemRegistry.REGISTRY.get(stack.getId());
        return data != null && data.name().endsWith("_chestplate");
    }

    /** Uses left before the item breaks. An elytra at 1 can no longer glide. */
    public static int durabilityLeft(ItemStack stack) {
        return ItemUtil.getDamageUntilBreak(stack);
    }

    public static List<ItemStack> inventory() {
        return CACHE.getPlayerCache().getPlayerInventory();
    }

    public static @Nullable ItemStack slot(int slot) {
        var inventory = inventory();
        return slot < inventory.size() ? inventory.get(slot) : null;
    }

    public static @Nullable ItemStack chest() {
        return slot(CHEST_SLOT);
    }

    /** Total item count matching the predicate across the main inventory, hotbar and offhand. */
    public static int count(Predicate<ItemStack> predicate) {
        int count = 0;
        for (int slot = MAIN_START; slot <= OFFHAND_SLOT; slot++) {
            var stack = slot(slot);
            if (stack != null && predicate.test(stack)) count += stack.getAmount();
        }
        return count;
    }

    /** True when no container is open and nothing is held on the cursor, so inventory clicks are safe. */
    public static boolean inventoryIdle() {
        var inventoryCache = CACHE.getPlayerCache().getInventoryCache();
        return inventoryCache.getOpenContainerId() == 0 && inventoryCache.getMouseStack() == null;
    }
}
