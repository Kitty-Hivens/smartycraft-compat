package dev.hivens.smartycompat.coremod;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Ports the IndustrialCraft 2 addons in these packs onto the IC2 API they
 * actually meet.
 *
 * Both were built against an IC2 whose inventory slots were constructed from a
 * TileEntityInventory. Current IC2 takes the interface instead:
 *
 *     InvSlot(IInventorySlotHolder&lt;?&gt;, String, Access, int, InvSide)
 *     InvSlotOutput(IInventorySlotHolder&lt;?&gt;, String, int)
 *     InvSlotProcessable(IInventorySlotHolder&lt;?&gt;, String, int, IMachineRecipeManager)
 *
 * and the old overloads are gone, so a stock jar throws NoSuchMethodError the
 * moment one of its machines is built. In Advanced Solar Panels, whose last
 * release is from December 2018, those three call sites are the only unresolved
 * references it has against the IC2 this pack ships. Advanced Machines, by the
 * same author, carries the same three in its heating machine, and the server's
 * copy of it differs from the published 61.0.1 in that one class and nothing
 * else.
 *
 * The fix is a descriptor rewrite, not a code change: TileEntityInventory
 * implements IInventorySlotHolder directly, so the value already on the stack
 * satisfies the new parameter and the verifier is content. Nothing about either
 * mod's behaviour moves.
 *
 * Narrow on purpose. Only constructor calls are considered, only when the owner
 * is one of IC2's slot classes, and only inside the two packages named here -- a
 * blanket descriptor substitution across every class the game loads would be a
 * much larger promise than this needs to make.
 */
public final class Ic2SlotHolderTransformer implements IClassTransformer {

    /** The two addons this reaches, both by the same author and both rebuilt. */
    private static final String[] TARGET_PACKAGES = {
        "com.chocohead.advsolar.",
        "com.chocohead.AdvMachines.",
    };

    private static final String SLOT_PACKAGE = "ic2/core/block/invslot/";

    private static final String OLD_PARAM = "Lic2/core/block/TileEntityInventory;";
    private static final String NEW_PARAM = "Lic2/core/block/IInventorySlotHolder;";

    private static final String CTOR = "<init>";

    private static boolean isTarget(String transformedName) {
        for (String pkg : TARGET_PACKAGES) {
            if (transformedName.startsWith(pkg)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || transformedName == null || !isTarget(transformedName)) {
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
                if (!CTOR.equals(call.name)
                    || call.owner == null
                    || !call.owner.startsWith(SLOT_PACKAGE)
                    || call.desc == null
                    || !call.desc.contains(OLD_PARAM)) {
                    continue;
                }
                call.desc = call.desc.replace(OLD_PARAM, NEW_PARAM);
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
