package dev.hivens.smartycompat.coremod;

import dev.hivens.smartycompat.runtime.CallerClass;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Points Railcraft's one caller lookup at an implementation that counts
 * stack frames the way the JDK 8 method it was written against did.
 *
 * DataManagerPlugin.create asks who called it so it can register a
 * DataParameter against that entity class:
 *
 *     Class&lt;?&gt; owner = sun.reflect.Reflection.getCallerClass(2);
 *     return EntityDataManager.createKey(owner.asSubclass(Entity.class), serializer);
 *
 * Depth 2 is correct for the original. That method is gone from modern
 * JVMs, so Cleanroom's Fugue redirects the call: it swaps the owner to
 * com.cleanroommc.hackery.Reflection and keeps the name and descriptor
 * untouched. The replacement counts one frame further out, so Railcraft
 * receives the class that loaded the entity rather than the entity, and
 * asSubclass throws during mod construction. Eleven cart classes reach
 * createKey through this one call site, which is the only place in
 * Railcraft that touches sun.reflect at all.
 *
 * The fix is the same move Fugue makes, to a target that counts correctly.
 * Both possible owners are matched because the two transformers are not
 * ordered against each other: on a plain Forge install the call still says
 * sun.reflect, and after Fugue has run it says Cleanroom. Either way the
 * result is a call that behaves identically on Java 8 and on a modern JVM,
 * since it no longer reaches sun.reflect at all.
 *
 * Deliberately confined to Railcraft's own class. Rewriting every caller
 * lookup in the game would also catch mods that have already been built
 * around the shifted numbering, and turn their working code into the bug
 * this removes.
 */
public final class RailcraftCallerClassTransformer implements IClassTransformer {

    private static final String TARGET_CLASS =
        "mods.railcraft.common.plugins.forge.DataManagerPlugin";

    private static final String JDK_OWNER = "sun/reflect/Reflection";
    private static final String FUGUE_OWNER = "com/cleanroommc/hackery/Reflection";

    private static final String LOOKUP_NAME = "getCallerClass";

    private static final String OUR_OWNER = CallerClass.class.getName().replace('.', '/');

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
                if (!(insn instanceof MethodInsnNode)) {
                    continue;
                }
                MethodInsnNode call = (MethodInsnNode) insn;
                if (!LOOKUP_NAME.equals(call.name)
                    || !(JDK_OWNER.equals(call.owner) || FUGUE_OWNER.equals(call.owner))) {
                    continue;
                }
                call.owner = OUR_OWNER;
                patched = true;
            }
        }

        if (!patched) {
            return basicClass;
        }

        // An owner swap moves nothing on the stack, so the frames and the
        // maxima the class already carries stay correct as they are.
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
