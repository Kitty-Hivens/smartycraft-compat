package dev.hivens.smartycompat.coremod;

import dev.hivens.smartycompat.runtime.MainMenuOwner;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Keeps the Betweenlands from taking the main menu back off the Aether.
 *
 * On every screen opened, the Betweenlands replaces anything that is a
 * {@code GuiMainMenu} and is not already its own with its own. That is
 * unconditional, so it wins every argument about the menu: the Aether's menu
 * extends {@code GuiMainMenu} as well, and would be swapped away the moment it
 * appeared. With `AetherMenuTakeoverTransformer` alone the two would trade the
 * screen back and forth.
 *
 * One more clause is added to the test the method already makes, excluding the
 * Aether's menu the same way the Betweenlands already excludes its own. The
 * clause jumps to the label the existing tests jump to, so the method gains no
 * jump target, and the Aether's menu is named through a helper rather than
 * referenced, so this holds in a pack without the Aether: there the name simply
 * never matches.
 *
 * This is not the server's behaviour. SmartyCraft turned both menus off in its
 * configs and never faced the question, so there is nothing to carry here and
 * this is ours.
 */
public final class BetweenlandsMainMenuTransformer implements IClassTransformer {

    private static final String TARGET_CLASS = "thebetweenlands.client.gui.menu.GuiBLMainMenu";

    private static final String BL_MENU = "thebetweenlands/client/gui/menu/GuiBLMainMenu";
    private static final String EVENT = "net/minecraftforge/client/event/GuiOpenEvent";
    private static final String GET_GUI = "getGui";
    private static final String GET_GUI_DESC = "()Lnet/minecraft/client/gui/GuiScreen;";

    private static final String OURS = MainMenuOwner.class.getName().replace('.', '/');
    private static final String OUR_NAME = "isAetherMenu";
    private static final String OUR_DESC = "(Lnet/minecraft/client/gui/GuiScreen;)Z";

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
            if (alreadyAsks(m)) {
                continue;
            }
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn.getOpcode() != Opcodes.INSTANCEOF
                    || !BL_MENU.equals(((TypeInsnNode) insn).desc)) {
                    continue;
                }
                AbstractInsnNode test = insn.getNext();
                if (!(test instanceof JumpInsnNode) || test.getOpcode() != Opcodes.IFNE) {
                    continue;
                }
                // The event is the only argument of a static handler, so slot 0.
                m.instructions.insert(test, new JumpInsnNode(
                    Opcodes.IFNE, ((JumpInsnNode) test).label));
                m.instructions.insert(test, new MethodInsnNode(
                    Opcodes.INVOKESTATIC, OURS, OUR_NAME, OUR_DESC, false));
                m.instructions.insert(test, new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, EVENT, GET_GUI, GET_GUI_DESC, false));
                m.instructions.insert(test, new VarInsnNode(Opcodes.ALOAD, 0));
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

    /** A method that already defers to the Aether is left alone. */
    private static boolean alreadyAsks(MethodNode m) {
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn instanceof MethodInsnNode && OURS.equals(((MethodInsnNode) insn).owner)) {
                return true;
            }
        }
        return false;
    }
}
