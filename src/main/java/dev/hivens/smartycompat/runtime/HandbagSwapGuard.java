package dev.hivens.smartycompat.runtime;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.item.ItemStack;

/**
 * Whether a click on the handbag is the hotbar swap that would move the bag
 * into its own contents.
 *
 * Pressing a hotbar number while pointing at a slot swaps that slot with the
 * numbered one. Point at a slot inside the open handbag and press the number
 * the handbag itself occupies, and the container is asked to put the bag into
 * the inventory the bag is providing. The server's copy refuses that click
 * outright rather than carrying it out.
 *
 * Identity, not equality: the question is whether the numbered hotbar slot
 * holds the very stack this container was opened on, which is what the
 * server's copy compares and what makes the click self-referential. Two
 * identical bags in different slots are a different situation and are left
 * alone.
 */
public final class HandbagSwapGuard {

    /** Hotbar slots are 0 through 8; a swap names one of those. */
    private static final int HOTBAR_SLOTS = 9;

    private HandbagSwapGuard() {
    }

    /**
     * @param clickType  the kind of click the player made
     * @param hotbarSlot the numbered slot a swap names, meaningless otherwise
     * @param player     whose inventory the numbered slot belongs to
     * @param bag        the stack this container was opened on
     */
    public static boolean swapsTheBagItself(
        ClickType clickType, int hotbarSlot, EntityPlayer player, ItemStack bag) {
        if (clickType != ClickType.SWAP || hotbarSlot < 0 || hotbarSlot >= HOTBAR_SLOTS) {
            return false;
        }
        if (player == null || player.inventory == null) {
            return false;
        }
        return player.inventory.getStackInSlot(hotbarSlot) == bag;
    }
}
