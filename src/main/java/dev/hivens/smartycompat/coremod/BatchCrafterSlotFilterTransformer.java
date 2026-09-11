package dev.hivens.smartycompat.coremod;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Carries the server's batch crafter slot filter over to a genuine
 * IndustrialCraft 2 jar.
 *
 * The ingredient slots of the batch crafter are anonymous InvSlots whose
 * accepts() drops the candidate stack into the cached crafting matrix and
 * asks the cached recipe whether it still matches:
 *
 *     ItemStack old = craftingGrid[slot];
 *     craftingGrid[slot] = stack;
 *     try {
 *         return recipe.matches(crafting, world);
 *     } finally {
 *         craftingGrid[slot] = old;
 *     }
 *
 * The server narrows that to the item the matrix already holds:
 *
 *     return recipe.matches(crafting, world)
 *         &amp;&amp; StackUtil.checkItemEqualityStrict(stack, old);
 *
 * which closes the gap between "any stack this recipe would accept here"
 * and "the stack this cached operation was set up for". An ore dictionary
 * sibling passes the first test and then yields the output cached for the
 * original ingredient.
 *
 * accepts() is one of the few parts of the batch crafter that runs on both
 * sides: it branches on world.isRemote to pick a recipe, so the client
 * evaluates it for slot validity in the open GUI. Everything else the
 * server changed -- canCraft(), doCrafting() -- is reached only from
 * updateEntityServer(), which a client never runs, so there is nothing
 * there to carry across.
 *
 * The rewrite is an insertion, not a replacement. The trailing
 * recipe.matches() call is located by owner and return type rather than by
 * name, and the conjunction is spliced in after it, leaving the enclosing
 * try/finally and the grid restore exactly as compiled. It is also
 * branchless: checkItemEqualityStrict only compares, so evaluating it
 * eagerly and folding with IAND gives the same answer as the short circuit
 * while adding no jump targets. That matters here, because a new branch
 * target would force frames to be recomputed, and computing frames inside
 * a transformer means asking the class loader for types it is still in the
 * middle of loading.
 */
public final class BatchCrafterSlotFilterTransformer implements IClassTransformer {

    private static final String TARGET_CLASS =
        "ic2.core.block.machine.tileentity.TileEntityBatchCrafter$4";

    private static final String TARGET_METHOD = "accepts";
    private static final String TARGET_DESC = "(Lnet/minecraft/item/ItemStack;)Z";

    private static final String RECIPE_INTERNAL = "net/minecraft/item/crafting/IRecipe";

    private static final String GRID_FIELD = "craftingGrid";

    private static final String STACK_UTIL = "ic2/core/util/StackUtil";
    private static final String EQUALITY_NAME = "checkItemEqualityStrict";
    private static final String EQUALITY_DESC =
        "(Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemStack;)Z";

    /** accepts(ItemStack) is an instance method, so the candidate stack is local 1. */
    private static final int CANDIDATE_STACK = 1;

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
            if (TARGET_METHOD.equals(m.name) && TARGET_DESC.equals(m.desc) && patch(m)) {
                patched = true;
                break;
            }
        }

        if (!patched) {
            return basicClass;
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean patch(MethodNode m) {
        int savedGridEntry = findSavedGridEntry(m);
        MethodInsnNode match = findTrailingRecipeMatch(m);
        if (savedGridEntry < 0 || match == null) {
            return false;
        }

        InsnList conjunction = new InsnList();
        conjunction.add(new VarInsnNode(Opcodes.ALOAD, CANDIDATE_STACK));
        conjunction.add(new VarInsnNode(Opcodes.ALOAD, savedGridEntry));
        conjunction.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC, STACK_UTIL, EQUALITY_NAME, EQUALITY_DESC, false));
        conjunction.add(new InsnNode(Opcodes.IAND));

        m.instructions.insert(match, conjunction);
        return true;
    }

    /**
     * Finds the local the method parks the displaced grid entry in, so the
     * inserted comparison reads the same value the finally block restores.
     * It is the store that follows the first read of craftingGrid.
     */
    private static int findSavedGridEntry(MethodNode m) {
        boolean seenGrid = false;
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn instanceof FieldInsnNode
                && insn.getOpcode() == Opcodes.GETFIELD
                && GRID_FIELD.equals(((FieldInsnNode) insn).name)) {
                seenGrid = true;
            } else if (seenGrid && insn.getOpcode() == Opcodes.ASTORE) {
                return ((VarInsnNode) insn).var;
            }
        }
        return -1;
    }

    /**
     * The method asks the recipe twice: once inside an assertion, once for
     * the answer it returns. Only the second is the result, so take the
     * last. Matching on owner and return type rather than on the method name
     * keeps this working whichever mapping the loaded jar carries.
     */
    private static MethodInsnNode findTrailingRecipeMatch(MethodNode m) {
        MethodInsnNode last = null;
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode)) {
                continue;
            }
            MethodInsnNode call = (MethodInsnNode) insn;
            if (RECIPE_INTERNAL.equals(call.owner) && call.desc.endsWith(")Z")) {
                last = call;
            }
        }
        return last;
    }
}
