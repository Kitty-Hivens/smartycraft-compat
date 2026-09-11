package dev.hivens.galaxycompat.coremod;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Drops Ender IO's version equality check on the server's mod list.
 *
 * Ender IO does not rely on Forge's own handshake comparison. It
 * registers a @NetworkCheckHandler which compiles down to
 *
 *     return remote.containsKey("enderio")
 *         &amp;&amp; "5.2.61".equals(remote.get("enderio"));
 *
 * with its own version inlined as a constant at build time. The check
 * runs on whatever the other side actually reported, so cloaking our
 * outbound mod list does not reach it: a client running a genuine
 * release refuses the server, by itself, before anything else happens.
 *
 * The server this pack targets runs a rebuild of release 5.2.61 that
 * was relabelled 5.2.0, and no such release exists to install. Without
 * this patch the only way to join is to ship the server's own jar.
 *
 * Only the equality half is removed: the other side must still HAVE
 * Ender IO. Returning a blind `true` would accept a server without it,
 * which the original check never did.
 *
 * Mod classes are not SRG-remapped at runtime -- only Minecraft's are
 * -- so transformedName matches the source name verbatim.
 */
public final class EnderIOVersionCheckTransformer implements IClassTransformer {

    private static final String TARGET_CLASS = "crazypants.enderio.base.EnderIO";

    private static final String TARGET_METHOD = "checkModLists";
    private static final String TARGET_DESC =
        "(Ljava/util/Map;Lnet/minecraftforge/fml/relauncher/Side;)Z";

    /** The mod id the original check required the other side to have. */
    private static final String REQUIRED_MODID = "enderio";

    private static final String MAP_INTERNAL      = "java/util/Map";
    private static final String CONTAINS_KEY_NAME = "containsKey";
    private static final String CONTAINS_KEY_DESC = "(Ljava/lang/Object;)Z";

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
            if (!TARGET_METHOD.equals(m.name) || !TARGET_DESC.equals(m.desc)) {
                continue;
            }

            m.instructions.clear();
            m.localVariables = null;
            m.tryCatchBlocks = null;

            // return remote.containsKey("enderio");
            // locals: 0 = this, 1 = the remote mod list, 2 = Side
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            m.instructions.add(new LdcInsnNode(REQUIRED_MODID));
            m.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                MAP_INTERNAL,
                CONTAINS_KEY_NAME,
                CONTAINS_KEY_DESC,
                true
            ));
            m.instructions.add(new InsnNode(Opcodes.IRETURN));

            patched = true;
            break;
        }

        if (!patched) {
            return basicClass;
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
