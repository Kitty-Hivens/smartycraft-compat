package dev.hivens.smartycompat.coremod;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Registers the villager profession Rats defines and never registers.
 *
 * Rats ships an {@code EntityPlagueDoctor}, its renderer, its trade, its village
 * structure and a {@code RatsVillageRegistry.PLAGUE_DOCTOR} profession, and then
 * {@code CommonProxy.registerVillagers} registers only the pet shop owner. The
 * profession is dead content in the published jar: the field is built and nothing
 * ever hands it to the registry.
 *
 * That is invisible until a server fixes it. The SmartyCraft RPG server's copy
 * adds the one missing call, so its world holds {@code rats:plague_doctor} as
 * villager profession 7, and a client on the published jar cannot answer for it:
 *
 * <pre>
 *     Registry VillagerProfession: Found a missing id from the world rats:plague_doctor
 *     Network Disconnect: Fatally missing registry entries
 * </pre>
 *
 * This is the first patch here that a handshake spoof could never have covered.
 * The others reconcile what the two sides *say*; this one is the registry sync,
 * which compares what they actually built. The client has to register the entry
 * for real, and the one call that does it is appended to the method that already
 * registers the profession beside it.
 *
 * The method is found by the profession it does register rather than by name or
 * descriptor, and the insert goes before the return the method already has, so
 * no jump target is added. A jar that already makes the call -- the server's own,
 * for a pack midway through repinning -- is left alone.
 */
public final class RatsPlagueDoctorTransformer implements IClassTransformer {

    private static final String TARGET_CLASS = "com.github.alexthe666.rats.server.CommonProxy";

    private static final String VILLAGE_REGISTRY =
        "com/github/alexthe666/rats/server/world/village/RatsVillageRegistry";
    private static final String PROFESSION_DESC =
        "Lnet/minecraftforge/fml/common/registry/VillagerRegistry$VillagerProfession;";
    private static final String PET_SHOP = "PET_SHOP_OWNER";
    private static final String PLAGUE_DOCTOR = "PLAGUE_DOCTOR";

    private static final String EVENT = "net/minecraftforge/event/RegistryEvent$Register";
    private static final String GET_REGISTRY = "getRegistry";
    private static final String GET_REGISTRY_DESC =
        "()Lnet/minecraftforge/registries/IForgeRegistry;";

    private static final String REGISTRY = "net/minecraftforge/registries/IForgeRegistry";
    private static final String REGISTER = "register";
    private static final String REGISTER_DESC =
        "(Lnet/minecraftforge/registries/IForgeRegistryEntry;)V";

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
            if (!registersProfession(m, PET_SHOP) || registersProfession(m, PLAGUE_DOCTOR)) {
                continue;
            }
            AbstractInsnNode end = finalReturn(m);
            if (end == null) {
                continue;
            }
            // event.getRegistry().register(RatsVillageRegistry.PLAGUE_DOCTOR)
            m.instructions.insertBefore(end, new VarInsnNode(Opcodes.ALOAD, 0));
            m.instructions.insertBefore(end, new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, EVENT, GET_REGISTRY, GET_REGISTRY_DESC, false));
            m.instructions.insertBefore(end, new FieldInsnNode(
                Opcodes.GETSTATIC, VILLAGE_REGISTRY, PLAGUE_DOCTOR, PROFESSION_DESC));
            m.instructions.insertBefore(end, new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, REGISTRY, REGISTER, REGISTER_DESC, true));
            patched = true;
        }

        if (!patched) {
            return basicClass;
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Whether this method hands the named profession to a registry. */
    private static boolean registersProfession(MethodNode m, String field) {
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn.getOpcode() != Opcodes.GETSTATIC) {
                continue;
            }
            FieldInsnNode f = (FieldInsnNode) insn;
            if (VILLAGE_REGISTRY.equals(f.owner) && field.equals(f.name)
                && PROFESSION_DESC.equals(f.desc)) {
                return true;
            }
        }
        return false;
    }

    /** The method's own return, which the new call is placed in front of. */
    private static AbstractInsnNode finalReturn(MethodNode m) {
        for (AbstractInsnNode insn = m.instructions.getLast(); insn != null;
             insn = insn.getPrevious()) {
            if (insn instanceof InsnNode && insn.getOpcode() == Opcodes.RETURN) {
                return insn;
            }
        }
        return null;
    }
}
