package dev.hivens.smartycompat.coremod;

import dev.hivens.smartycompat.runtime.TexFixGuard;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Keeps TexFix from reloading a sprite on a thread with no OpenGL context.
 *
 * TexFix 4.0 frees the frame data of every still sprite once the textures
 * are stitched, and reads it back from disk on first access, on whatever
 * thread asked. For a sprite with a custom loader the read back is a second
 * call to that loader, which Forge only ever makes while stitching, on the
 * render thread. Railcraft's loader registers a GL texture there, so when
 * JourneyMap samples a rail's colour from its own worker thread the reload
 * calls glGenTextures without a context and the JVM aborts.
 *
 * Two rewrites, each one instruction swapped for a static call that leaves
 * the operand stack as it was, so frames and maxima are untouched:
 *
 * In the reload listener ({@code TextureFix$1}), the check that keeps
 * animated sprites out of the scheme is widened to keep sprites with a
 * custom loader out too. Their frames stay in memory, as they would without
 * TexFix. Every other sprite is freed exactly as before.
 *
 * In {@code TextureFix}, the queue of sprites to free again is a plain
 * LinkedList that a reload appends to from any thread while the client tick
 * drains it. Its four calls go through methods that hold the list's monitor.
 *
 * Nothing here names a TexFix class. The transformer matches on the class
 * name it is offered, so in a pack without TexFix it never matches and
 * changes nothing, and the runtime side only ever touches Minecraft's own
 * types and the list it is handed.
 */
public final class TexFixLoadGuardTransformer implements IClassTransformer {

    private static final String LISTENER = "texFix.TextureFix$1";
    private static final String MAIN = "texFix.TextureFix";

    private static final String SPRITE = "net/minecraft/client/renderer/texture/TextureAtlasSprite";
    /** hasAnimationMetadata, by its SRG name in a pack and its MCP name in a dev run. */
    private static final String HAS_ANIMATION_SRG = "func_130098_m";
    private static final String HAS_ANIMATION_MCP = "hasAnimationMetadata";

    private static final String LIST = "java/util/LinkedList";

    private static final String OUR_OWNER = TexFixGuard.class.getName().replace('.', '/');

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || transformedName == null) {
            return basicClass;
        }
        if (LISTENER.equals(transformedName)) {
            return rewrite(basicClass, true);
        }
        if (MAIN.equals(transformedName)) {
            return rewrite(basicClass, false);
        }
        return basicClass;
    }

    private static byte[] rewrite(byte[] basicClass, boolean listener) {
        ClassReader reader = new ClassReader(basicClass);
        ClassNode   node   = new ClassNode();
        reader.accept(node, 0);

        boolean patched = false;
        for (MethodNode m : node.methods) {
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode) || insn.getOpcode() != Opcodes.INVOKEVIRTUAL) {
                    continue;
                }
                MethodInsnNode call = (MethodInsnNode) insn;
                MethodInsnNode replacement = listener ? spriteCheck(call) : queueCall(call);
                if (replacement != null) {
                    m.instructions.set(insn, replacement);
                    patched = true;
                }
            }
        }

        if (!patched) {
            return basicClass;
        }

        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** sprite.hasAnimationMetadata() becomes TexFixGuard.leaveAlone(sprite). */
    private static MethodInsnNode spriteCheck(MethodInsnNode call) {
        if (!SPRITE.equals(call.owner) || !"()Z".equals(call.desc)
            || !(HAS_ANIMATION_SRG.equals(call.name) || HAS_ANIMATION_MCP.equals(call.name))) {
            return null;
        }
        return new MethodInsnNode(Opcodes.INVOKESTATIC, OUR_OWNER, "leaveAlone",
            "(L" + SPRITE + ";)Z", false);
    }

    /** list.add/size/getFirst/removeFirst become the same calls under the list's monitor. */
    private static MethodInsnNode queueCall(MethodInsnNode call) {
        if (!LIST.equals(call.owner)) {
            return null;
        }
        String desc;
        if ("add".equals(call.name) && "(Ljava/lang/Object;)Z".equals(call.desc)) {
            desc = "(L" + LIST + ";Ljava/lang/Object;)Z";
        } else if ("size".equals(call.name) && "()I".equals(call.desc)) {
            desc = "(L" + LIST + ";)I";
        } else if (("getFirst".equals(call.name) || "removeFirst".equals(call.name))
            && "()Ljava/lang/Object;".equals(call.desc)) {
            desc = "(L" + LIST + ";)Ljava/lang/Object;";
        } else {
            return null;
        }
        return new MethodInsnNode(Opcodes.INVOKESTATIC, OUR_OWNER, call.name, desc, false);
    }
}
