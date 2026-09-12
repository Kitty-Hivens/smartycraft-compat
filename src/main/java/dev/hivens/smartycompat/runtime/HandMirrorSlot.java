package dev.hivens.smartycompat.runtime;

import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;

/**
 * Finds the hand mirror the container was opened with, in either hand.
 *
 * Thaumcraft's hand mirror container takes whatever is in the selected hotbar
 * slot and calls it the mirror. Open the mirror from the off hand and the
 * container is built around the main hand's item instead, so the client shows
 * a container that has nothing to do with what the player used. The server's
 * copy falls back to the off hand when the main hand is not holding one.
 *
 * Only that fallback is carried. The server's copy also nulls the field when
 * neither hand holds a mirror, and closes the screen on that null elsewhere.
 * Feeding null into a jar whose other methods have never had to expect it
 * would trade a cosmetic mismatch for a crash, so a miss answers with the
 * empty stack instead, which is exactly what the published release already
 * puts there when the hand is empty.
 */
public final class HandMirrorSlot {

    private HandMirrorSlot() {
    }

    /**
     * @param current    what the container picked from the selected hotbar slot
     * @param inventory  the player inventory the container was built from
     * @param mirrorType the mod's own mirror item class, read out of the class
     *                   being patched rather than named here
     */
    public static ItemStack resolve(ItemStack current, InventoryPlayer inventory, Class<?> mirrorType) {
        if (isMirror(current, mirrorType)) {
            return current;
        }
        if (inventory != null && !inventory.offHandInventory.isEmpty()) {
            ItemStack offHand = inventory.offHandInventory.get(0);
            if (isMirror(offHand, mirrorType)) {
                return offHand;
            }
        }
        return current == null ? ItemStack.EMPTY : current;
    }

    private static boolean isMirror(ItemStack stack, Class<?> mirrorType) {
        return stack != null && !stack.isEmpty() && mirrorType.isInstance(stack.getItem());
    }
}
