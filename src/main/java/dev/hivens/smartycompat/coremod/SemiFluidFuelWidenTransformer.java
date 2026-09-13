package dev.hivens.smartycompat.coremod;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Ports BC Fuels For IC2 onto the IndustrialCraft 2 API it actually meets.
 *
 * The published v0.2 registers its eight fuels through
 *
 *     ISemiFluidFuelManager.addFluid(String, int, double)
 *
 * and current IC2 declares only
 *
 *     ISemiFluidFuelManager.addFluid(String, long, long)
 *
 * so the stock jar throws NoSuchMethodError while registering the first one.
 * The server's copy differs from the published release in this one class and
 * nothing else, which is the same shape as the two addons in
 * {@link Ic2SlotHolderTransformer}: a forced recompile, not a change of
 * behaviour.
 *
 * Unlike those, a descriptor rewrite alone will not do. The arguments are of
 * the wrong kinds and not merely of the wrong declared types, so the two on the
 * stack have to be widened: the amount from int, and the energy from double,
 * both to long. The double sits above the int, so it is parked in a local while
 * the int underneath it is widened, then brought back and widened in place.
 *
 * Truncating the energy is what the newer API asks for. IC2 stopped expressing
 * it as a fraction, and every value this mod passes comes from its own config
 * as a whole number anyway.
 *
 * No branch and no jump target, so nothing in the method's frames is disturbed.
 * The scratch local is taken past the end of the frame and the writer sizes it.
 */
public final class SemiFluidFuelWidenTransformer implements IClassTransformer {

    private static final String TARGET_CLASS = "com.xxTFxx.bcfuelsforic2.BCFuelsForIC2";

    private static final String MANAGER = "ic2/api/recipe/ISemiFluidFuelManager";
    private static final String ADD_FLUID = "addFluid";
    private static final String OLD_DESC = "(Ljava/lang/String;ID)V";
    private static final String NEW_DESC = "(Ljava/lang/String;JJ)V";

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
            // Past the end of the frame, so nothing live is overwritten. A
            // double occupies this slot and the next; the writer grows the
            // method to fit.
            int scratch = m.maxLocals;
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode)) {
                    continue;
                }
                MethodInsnNode call = (MethodInsnNode) insn;
                if (!MANAGER.equals(call.owner)
                    || !ADD_FLUID.equals(call.name)
                    || !OLD_DESC.equals(call.desc)) {
                    continue;
                }

                InsnList widen = new InsnList();
                widen.add(new VarInsnNode(Opcodes.DSTORE, scratch));
                widen.add(new InsnNode(Opcodes.I2L));
                widen.add(new VarInsnNode(Opcodes.DLOAD, scratch));
                widen.add(new InsnNode(Opcodes.D2L));
                m.instructions.insertBefore(call, widen);

                call.desc = NEW_DESC;
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
