package dev.hivens.smartycompat.runtime;

import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;

/**
 * Writes a stack back to the hand it was taken from.
 *
 * Thermal Expansion's Cache hands the player a stack when they interact with
 * it, and the published release always writes that stack into the selected
 * hotbar slot, whichever hand actually did the interacting. Reach for the
 * Cache with the off hand and the main hand's item is what gets replaced.
 * The server's copy branches on the hand and writes the off hand slot when
 * that is the one being used.
 *
 * Block activation runs on both sides, so an unpatched client predicts the
 * wrong slot and shows the swap in the wrong place until the server's next
 * window update puts it right.
 */
public final class HeldStackWrite {

    private HeldStackWrite() {
    }

    public static void setHeldStack(InventoryPlayer inventory, int selectedSlot,
                                    ItemStack stack, EnumHand hand) {
        if (hand == EnumHand.MAIN_HAND) {
            inventory.setInventorySlotContents(selectedSlot, stack);
        } else {
            inventory.offHandInventory.set(0, stack);
        }
    }
}
