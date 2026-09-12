package dev.hivens.smartycompat.coremod;

import dev.hivens.smartycompat.runtime.UncraftingAssembly;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Carries the server's settling of the uncrafting table's assembly matrix.
 *
 * Twilight Forest's goblin craft result slot clears the uncrafting matrix and
 * charges the experience cost, then hands off to the vanilla crafting slot.
 * The server's copy halves whatever each assembly slot still holds once that
 * has returned, so the ingredients cannot be drawn twice, and it does so only
 * on the uncrafting path: a result equal to what a plain recipe would make is
 * ordinary crafting and is left alone.
 *
 * Container slots are evaluated on both sides, so without this the client
 * predicts an assembly matrix the server does not agree with.
 *
 * The rewrite appends to the tail call rather than rebuilding the method. The
 * published release reaches its single super.onTake from both paths, so the
 * flag the method already computed is pushed alongside the result and the
 * matrix, and the decision is made in ordinary Java. Three pushes and one
 * call, no new jump targets, so no frame is recomputed.
 *
 * The flag is found rather than assumed: it is the local feeding the one
 * IFEQ in the method that a plain load reaches, which is the branch between
 * uncrafting and crafting. The matrix is read through the field the class
 * itself declares.
 */
public final class UncraftingTakeTransformer implements IClassTransformer {

    private static final String TARGET_CLASS = "twilightforest.inventory.SlotTFGoblinCraftResult";

    private static final String SLOT_CRAFTING = "net/minecraft/inventory/SlotCrafting";

    private static final String MATRIX_FIELD = "assemblyMatrix";
    private static final String MATRIX_DESC = "Lnet/minecraft/inventory/InventoryCrafting;";

    private static final String OUR_OWNER = UncraftingAssembly.class.getName().replace('.', '/');
    private static final String OUR_NAME = "afterTake";
    private static final String OUR_DESC =
        "(Lnet/minecraft/item/ItemStack;Lnet/minecraft/inventory/InventoryCrafting;Z)"
        + "Lnet/minecraft/item/ItemStack;";

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
            MethodInsnNode take = superTake(m);
            int flag = flagLocal(m);
            if (take == null || flag < 0) {
                continue;
            }

            InsnList tail = new InsnList();
            tail.add(new VarInsnNode(Opcodes.ALOAD, 0));
            tail.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, MATRIX_FIELD, MATRIX_DESC));
            tail.add(new VarInsnNode(Opcodes.ILOAD, flag));
            tail.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, OUR_OWNER, OUR_NAME, OUR_DESC, false));

            m.instructions.insert(take, tail);
            patched = true;
            break;
        }

        if (!patched) {
            return basicClass;
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** The hand-off to the vanilla crafting slot, whose result the method returns. */
    private static MethodInsnNode superTake(MethodNode m) {
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn.getOpcode() != Opcodes.INVOKESPECIAL) {
                continue;
            }
            MethodInsnNode call = (MethodInsnNode) insn;
            if (SLOT_CRAFTING.equals(call.owner)
                && call.desc.endsWith(")Lnet/minecraft/item/ItemStack;")) {
                return call;
            }
        }
        return null;
    }

    /**
     * The local holding "this was an uncraft, not a craft". It is the one that
     * a plain integer load feeds straight into an IFEQ, which is the only such
     * branch in the method.
     */
    private static int flagLocal(MethodNode m) {
        int found = -1;
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (!(insn instanceof JumpInsnNode) || insn.getOpcode() != Opcodes.IFEQ) {
                continue;
            }
            AbstractInsnNode prev = insn.getPrevious();
            if (prev instanceof VarInsnNode && prev.getOpcode() == Opcodes.ILOAD) {
                if (found >= 0) {
                    return -1;
                }
                found = ((VarInsnNode) prev).var;
            }
        }
        return found;
    }
}
