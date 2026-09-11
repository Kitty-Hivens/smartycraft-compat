package dev.hivens.smartycompat.runtime;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/**
 * The item comparison the server's AE2 Stuff uses to decide what an
 * inscriber slot will take.
 *
 * The published release compares two stacks with BdLib's isSameItem, which
 * ends in {@code ItemStack.areItemStackTagsEqual}. That helper requires two
 * things: equal NBT, and compatible capabilities. The server's copy asks
 * only for equal NBT, so it accepts a pair the published one rejects when
 * the two stacks carry capabilities that do not compare equal.
 *
 * That reaches a client. The inscriber's isItemValidForSlot runs through
 * isValidPartialRecipe to this comparison, and slot validity is evaluated
 * on both sides, so an unpatched client refuses to place an ingredient the
 * server would have taken.
 *
 * Everything else is kept: the same null handling, the same item equality,
 * and metadata compared only for items that have subtypes.
 *
 * The leading parameter is the BdLib singleton the original call was made
 * on. Accepting and ignoring it lets the rewrite swap one instruction and
 * leave the operand stack exactly as it was, which is the same shape a
 * mixin redirect handler takes for the same reason.
 */
public final class InscriberMatch {

    private InscriberMatch() {
    }

    public static boolean isSameItem(Object receiver, ItemStack a, ItemStack b) {
        if (a == null || b == null) {
            return a == b;
        }

        if (a.getItem() == null ? b.getItem() != null : !a.getItem().equals(b.getItem())) {
            return false;
        }

        if (a.getHasSubtypes() && b.getItemDamage() != a.getItemDamage()) {
            return false;
        }

        NBTTagCompound tagA = a.getTagCompound();
        NBTTagCompound tagB = b.getTagCompound();
        return tagA == null ? tagB == null : tagA.equals(tagB);
    }
}
