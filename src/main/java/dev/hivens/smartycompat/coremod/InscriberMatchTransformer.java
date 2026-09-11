package dev.hivens.smartycompat.coremod;

import dev.hivens.smartycompat.runtime.InscriberMatch;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Carries the server's inscriber slot filter over to a genuine AE2 Stuff jar.
 *
 * The published release asks BdLib whether two stacks are the same item, and
 * BdLib finishes with {@code ItemStack.areItemStackTagsEqual}, which demands
 * equal NBT and compatible capabilities. The server's copy compares NBT
 * alone, so it takes ingredients the published one turns away.
 *
 * The inscriber's isItemValidForSlot reaches the comparison through
 * isValidPartialRecipe, and slot validity is evaluated on both sides, so
 * without this an unpatched client refuses items the server would accept.
 *
 * The rewrite swaps one instruction per call site: the invokevirtual on
 * BdLib's singleton becomes an invokestatic on our own comparison, which
 * takes that singleton as an ignored leading parameter. Operand stack,
 * frames and maxima are therefore all unchanged, and nothing has to be
 * recomputed inside a transformer.
 *
 * Scoped to the inscriber's own package. BdLib's comparison is used all over
 * AE2 Stuff and by other mods that ship it, and only the inscriber's use of
 * it differs on the server.
 */
public final class InscriberMatchTransformer implements IClassTransformer {

    private static final String TARGET_PACKAGE = "net.bdew.ae2stuff.machines.inscriber.";

    private static final String BDLIB_OWNER = "net/bdew/lib/items/ItemUtils$";
    private static final String BDLIB_NAME = "isSameItem";
    private static final String BDLIB_DESC =
        "(Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemStack;)Z";

    private static final String OUR_OWNER = InscriberMatch.class.getName().replace('.', '/');
    private static final String OUR_DESC =
        "(Ljava/lang/Object;Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemStack;)Z";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || transformedName == null
            || !transformedName.startsWith(TARGET_PACKAGE)) {
            return basicClass;
        }

        ClassReader reader = new ClassReader(basicClass);
        ClassNode   node   = new ClassNode();
        reader.accept(node, 0);

        boolean patched = false;
        for (MethodNode m : node.methods) {
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode)) {
                    continue;
                }
                MethodInsnNode call = (MethodInsnNode) insn;
                if (!BDLIB_OWNER.equals(call.owner)
                    || !BDLIB_NAME.equals(call.name)
                    || !BDLIB_DESC.equals(call.desc)) {
                    continue;
                }
                m.instructions.set(insn, new MethodInsnNode(
                    Opcodes.INVOKESTATIC, OUR_OWNER, BDLIB_NAME, OUR_DESC, false));
                patched = true;
            }
        }

        if (!patched) {
            return basicClass;
        }

        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
