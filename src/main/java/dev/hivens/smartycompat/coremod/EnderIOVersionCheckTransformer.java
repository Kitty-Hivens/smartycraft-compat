package dev.hivens.smartycompat.coremod;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Drops Ender IO's version equality checks on the server's mod list.
 *
 * Ender IO does not rely on Forge's own handshake comparison. It registers a
 * {@code @NetworkCheckHandler} which compiles down to
 *
 *     return remote.keySet().contains("enderio")
 *         &amp;&amp; "5.2.61".equals(remote.get("enderio"));
 *
 * with its own version inlined as a constant at build time. The check runs on
 * whatever the other side actually reported, so cloaking our outbound mod list
 * does not reach it: a client running a genuine release refuses the server, by
 * itself, before anything else happens.
 *
 * The server this pack targets runs a rebuild of release 5.2.61 that was
 * relabelled 5.2.0, and no such release exists to install.
 *
 * Ender IO ships as eleven mod ids, and ten of its classes carry a handler of
 * their own, each asking after the module it belongs to: the base mod, the
 * conduits, the three conduit integrations, the two Tinkers integrations, the
 * Forestry integration, the machines and the power tools. Patching only the
 * first would leave nine others to refuse the connection, so every class in
 * Ender IO's package tree that declares the method is rewritten, and the mod
 * id each one requires is read out of its own body rather than assumed.
 *
 * Only the equality half is removed: the other side must still HAVE that
 * module. Returning a blind {@code true} would accept a server without it,
 * which the original check never did.
 *
 * Mod classes are not SRG-remapped at runtime -- only Minecraft's are -- so
 * transformedName matches the source name verbatim.
 */
public final class EnderIOVersionCheckTransformer implements IClassTransformer {

    private static final String TARGET_PACKAGE = "crazypants.enderio.";

    private static final String TARGET_METHOD = "checkModLists";
    private static final String TARGET_DESC =
        "(Ljava/util/Map;Lnet/minecraftforge/fml/relauncher/Side;)Z";

    private static final String MAP_INTERNAL = "java/util/Map";
    private static final String SET_INTERNAL = "java/util/Set";
    private static final String CONTAINS_KEY_NAME = "containsKey";
    private static final String CONTAINS_NAME = "contains";
    private static final String CONTAINS_DESC = "(Ljava/lang/Object;)Z";

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
            if (!TARGET_METHOD.equals(m.name) || !TARGET_DESC.equals(m.desc)) {
                continue;
            }
            String modid = requiredModid(m);
            if (modid == null) {
                continue;
            }

            m.instructions.clear();
            m.localVariables = null;
            m.tryCatchBlocks = null;

            // return remote.containsKey(modid);
            // locals: 0 = this, 1 = the remote mod list, 2 = Side
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            m.instructions.add(new LdcInsnNode(modid));
            m.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, MAP_INTERNAL, CONTAINS_KEY_NAME, CONTAINS_DESC, true));
            m.instructions.add(new InsnNode(Opcodes.IRETURN));

            patched = true;
        }

        if (!patched) {
            return basicClass;
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * The mod id this handler demands the other side have, taken from the
     * constant the original presence test is given. Each module asks after
     * itself, so reading it here keeps every one of them honest instead of
     * assuming they all ask after the base mod.
     */
    private static String requiredModid(MethodNode m) {
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode)) {
                continue;
            }
            MethodInsnNode call = (MethodInsnNode) insn;
            boolean presenceTest =
                (SET_INTERNAL.equals(call.owner) && CONTAINS_NAME.equals(call.name))
                || (MAP_INTERNAL.equals(call.owner) && CONTAINS_KEY_NAME.equals(call.name));
            if (!presenceTest || !CONTAINS_DESC.equals(call.desc)) {
                continue;
            }
            AbstractInsnNode prev = call.getPrevious();
            if (prev instanceof LdcInsnNode && ((LdcInsnNode) prev).cst instanceof String) {
                return (String) ((LdcInsnNode) prev).cst;
            }
        }
        return null;
    }
}
