package dev.hivens.smartycompat.coremod;

import dev.hivens.smartycompat.runtime.MainMenuOwner;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Lets the Aether's menu toggle do something when another mod owns the menu.
 *
 * The Aether adds its toggle button to any screen that {@code instanceof
 * GuiMainMenu}, and then decides whether to install its own menu by asking
 * whether the screen's class is exactly {@code GuiMainMenu}. The two tests
 * disagree about what a main menu is, and the Betweenlands sits in the gap: its
 * menu extends {@code GuiMainMenu}, so the button is drawn on it, and the
 * replacement never fires however the button is set. Pressing it flips the
 * config and nothing else happens.
 *
 * The exact-class test becomes "a main menu that is not already the Aether's
 * own", which is what it was reaching for. It has to exclude the Aether's menu
 * explicitly: that menu extends {@code GuiMainMenu} too, so a plain
 * {@code instanceof} would reopen the screen it had just opened, forever. The
 * original exact-class comparison ruled that out by accident, and the
 * replacement has to rule it out on purpose.
 *
 * The site is found by the only class constant of {@code GuiMainMenu} the class
 * loads, between the {@code getClass} that produced the left side and the
 * reference comparison that consumes both. The other two comparisons of this
 * shape name the Aether's own menu and the skin screen.
 *
 * `BetweenlandsMainMenuTransformer` is the other half; neither is any use alone.
 */
public final class AetherMenuTakeoverTransformer implements IClassTransformer {

    private static final String TARGET_CLASS = "com.gildedgames.the_aether.client.AetherClientEvents";

    private static final String MAIN_MENU = "net/minecraft/client/gui/GuiMainMenu";
    private static final String OBJECT = "java/lang/Object";
    private static final String GET_CLASS = "getClass";

    private static final String OURS = MainMenuOwner.class.getName().replace('.', '/');
    private static final String OUR_NAME = "aetherShouldTakeOver";
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
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (!isMainMenuConstant(insn)) {
                    continue;
                }
                AbstractInsnNode left = insn.getPrevious();
                AbstractInsnNode test = insn.getNext();
                if (!isGetClass(left) || !(test instanceof JumpInsnNode)
                    || test.getOpcode() != Opcodes.IF_ACMPNE) {
                    continue;
                }
                // The screen is already on the stack where getClass found it, so
                // the pair of operands collapses to one call and one branch.
                m.instructions.set(left, new MethodInsnNode(
                    Opcodes.INVOKESTATIC, OURS, OUR_NAME, OUR_DESC, false));
                m.instructions.remove(insn);
                m.instructions.set(test, new JumpInsnNode(
                    Opcodes.IFEQ, ((JumpInsnNode) test).label));
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

    private static boolean isMainMenuConstant(AbstractInsnNode insn) {
        if (!(insn instanceof LdcInsnNode)) {
            return false;
        }
        Object cst = ((LdcInsnNode) insn).cst;
        return cst instanceof Type && MAIN_MENU.equals(((Type) cst).getInternalName());
    }

    private static boolean isGetClass(AbstractInsnNode insn) {
        if (insn == null || insn.getOpcode() != Opcodes.INVOKEVIRTUAL) {
            return false;
        }
        MethodInsnNode call = (MethodInsnNode) insn;
        return OBJECT.equals(call.owner) && GET_CLASS.equals(call.name);
    }
}
