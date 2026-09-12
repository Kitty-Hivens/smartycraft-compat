package dev.hivens.smartycompat.runtime;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;

/**
 * Settles the uncrafting table's assembly matrix once its result is taken.
 *
 * Twilight Forest's goblin craft result slot clears the uncrafting matrix and
 * charges the experience cost, then hands off to the vanilla crafting slot,
 * which consumes the assembly matrix in its own way. The published release
 * stops there. The server's copy halves what each assembly slot still holds
 * afterwards, so the ingredients cannot be taken twice over.
 *
 * Only the uncrafting path is touched. When the result is the same stack a
 * plain recipe would have produced, the slot is doing ordinary crafting and
 * the server leaves it alone, so the flag the method already computed is
 * passed through rather than re-derived.
 */
public final class UncraftingAssembly {

    private UncraftingAssembly() {
    }

    /**
     * @param result   what the slot is about to return, passed through
     * @param assembly the matrix the crafting slot has just drawn from
     * @param uncrafted the method's own flag: false means a plain craft
     */
    public static ItemStack afterTake(ItemStack result, InventoryCrafting assembly, boolean uncrafted) {
        if (uncrafted && assembly != null) {
            for (int slot = 0; slot < assembly.getSizeInventory(); slot++) {
                ItemStack held = assembly.getStackInSlot(slot);
                if (!held.isEmpty()) {
                    held.setCount(held.getCount() / 2);
                }
            }
        }
        return result;
    }
}
