package dev.hivens.smartycompat.coremod;

import dev.hivens.smartycompat.runtime.HeldStackWrite;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Carries the server's off hand handling for Thermal Expansion's Cache.
 *
 * The published release writes the stack the Cache hands back into the
 * selected hotbar slot no matter which hand was used, so interacting with
 * the off hand replaces the main hand's item. The server's copy branches on
 * the hand and writes the off hand slot instead when that is the one in use.
 *
 * Block activation runs on both sides, and Thermal Expansion reaches this
 * delegate without a side check, so an unpatched client predicts the wrong
 * slot and shows the swap in the wrong place until the next window update.
 *
 * The branch lives in ordinary Java. All the rewrite does is push the hand,
 * which the method already holds in local 5, and turn the inventory write
 * into a call that takes it. One extra operand and no new jump targets, so
 * no stack map frame has to be recomputed, which is not something to attempt
 * from inside a transformer.
 *
 * The write is matched by owner and descriptor rather than by name, so it
 * does not matter which mapping the loaded jar carries. There is exactly one
 * such write in the method.
 */
public final class CacheOffHandTransformer implements IClassTransformer {

    private static final String TARGET_CLASS = "cofh.thermalexpansion.block.storage.BlockCache";

    private static final String TARGET_METHOD = "onBlockActivatedDelegate";

    private static final String INVENTORY_OWNER = "net/minecraft/entity/player/InventoryPlayer";
    private static final String WRITE_DESC = "(ILnet/minecraft/item/ItemStack;)V";

    /** onBlockActivatedDelegate(World, BlockPos, IBlockState, EntityPlayer, EnumHand, ...). */
    private static final int HAND_LOCAL = 5;

    private static final String OUR_OWNER = HeldStackWrite.class.getName().replace('.', '/');
    private static final String OUR_NAME = "setHeldStack";
    private static final String OUR_DESC =
        "(Lnet/minecraft/entity/player/InventoryPlayer;ILnet/minecraft/item/ItemStack;"
        + "Lnet/minecraft/util/EnumHand;)V";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || !TARGET_CLASS.equals(transformedName)) {
            return basicClass;
        }

        ClassReader reader = new ClassReader(basicClass);
        ClassNode   node   = new ClassNode();
        reader.accept(node, 0);

        boolean patched = false;
        for (MethodNode m : node.methods) {
            if (!TARGET_METHOD.equals(m.name)) {
                continue;
            }
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode)) {
                    continue;
                }
                MethodInsnNode call = (MethodInsnNode) insn;
                if (!INVENTORY_OWNER.equals(call.owner) || !WRITE_DESC.equals(call.desc)) {
                    continue;
                }
                m.instructions.insertBefore(insn, new VarInsnNode(Opcodes.ALOAD, HAND_LOCAL));
                m.instructions.set(insn, new MethodInsnNode(
                    Opcodes.INVOKESTATIC, OUR_OWNER, OUR_NAME, OUR_DESC, false));
                patched = true;
            }
        }

        if (!patched) {
            return basicClass;
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
