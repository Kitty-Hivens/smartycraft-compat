package dev.hivens.smartycompat.coremod;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Lets the Aether generate its beta texture pack on a system that is not Windows.
 *
 * Aether Legacy writes a "Aether b1.7.3 Textures" resource pack into the
 * resourcepacks folder on first run, on by default, and it builds the four item
 * directories it needs by pasting backslashes into a path:
 *
 * <pre>
 *     new File(resourcePacks + "\\Aether b1.7.3 Textures\\assets\\...\\buckets")
 * </pre>
 *
 * A backslash is an ordinary filename character everywhere except Windows, so on
 * Linux that is not four directories but one, whose name contains the whole rest
 * of the path. What lands in resourcepacks is a directory called
 * {@code Aether b1.7.3 Textures\assets\aether_legacy\textures\items\misc\buckets},
 * and the pack itself is left without its item textures.
 *
 * The mod is inconsistent rather than Windows-only: the same method writes
 * {@code pack.mcmeta} and {@code pack.png} through {@code "/"} two lines further
 * down, so the pack directory is created correctly and only the contents go
 * astray. A forward slash is accepted by Windows too, which is why the fix is to
 * make the four strings agree with the rest of the method rather than to ask the
 * platform what its separator is.
 *
 * Every string constant in the class that names the pack directory and carries a
 * backslash is rewritten, which is four of them, rather than the four literals
 * being repeated here: the paths are long, the mod has changed them between
 * releases, and a literal that no longer matches would fail silently. The
 * server's own build already made this change, so its jar offers nothing to
 * match and the patch is a no-op there.
 */
public final class AetherTexturePackPathTransformer implements IClassTransformer {

    private static final String TARGET_CLASS = "com.gildedgames.the_aether.client.ClientProxy";

    /** The generated pack's directory, which every affected path starts with. */
    private static final String PACK_DIR = "Aether b1.7.3 Textures";

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
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (!(insn instanceof LdcInsnNode)) {
                    continue;
                }
                LdcInsnNode ldc = (LdcInsnNode) insn;
                if (!(ldc.cst instanceof String)) {
                    continue;
                }
                String path = (String) ldc.cst;
                if (path.indexOf('\\') < 0 || !path.contains(PACK_DIR)) {
                    continue;
                }
                // A constant swap: same opcode, same one value on the stack.
                m.instructions.set(ldc, new LdcInsnNode(path.replace('\\', '/')));
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
