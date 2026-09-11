package dev.hivens.smartycompat.coremod;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Ports Advanced Solar Panels 4.3.0 onto the IndustrialCraft 2 API it
 * meets in this pack.
 *
 * ASP's last release is from December 2018 and was built against an
 * IC2 whose inventory slots were constructed from a TileEntityInventory.
 * Current IC2 takes the interface instead:
 *
 *     InvSlot(IInventorySlotHolder&lt;?&gt;, String, Access, int, InvSide)
 *     InvSlotOutput(IInventorySlotHolder&lt;?&gt;, String, int)
 *     InvSlotProcessable(IInventorySlotHolder&lt;?&gt;, String, int, IMachineRecipeManager)
 *
 * and the old overloads are gone, so the stock jar throws
 * NoSuchMethodError the moment a Molecular Assembler is built. Those
 * three call sites are the only unresolved references the release has
 * against the IC2 this pack ships.
 *
 * The fix is a descriptor rewrite, not a code change: TileEntityInventory
 * implements IInventorySlotHolder directly, so the value already on the
 * stack satisfies the new parameter and the verifier is content. Nothing
 * about the mod's behaviour moves.
 *
 * Narrow on purpose. Only constructor calls are considered, only when
 * the owner is one of IC2's slot classes, and only inside ASP's own
 * package -- a blanket descriptor substitution across every class the
 * game loads would be a much larger promise than this needs to make.
 */
public final class AdvancedSolarSlotApiTransformer implements IClassTransformer {

    private static final String TARGET_PACKAGE = "com.chocohead.advsolar.";

    private static final String SLOT_PACKAGE = "ic2/core/block/invslot/";

    private static final String OLD_PARAM = "Lic2/core/block/TileEntityInventory;";
    private static final String NEW_PARAM = "Lic2/core/block/IInventorySlotHolder;";

    private static final String CTOR = "<init>";

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
