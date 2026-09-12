package dev.hivens.smartycompat.coremod;

import dev.hivens.smartycompat.runtime.ChatHeads;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Carries the server's chat heads onto the published Better Chat release.
 *
 * The server draws the sender's head, hat layer and all, beside each chat
 * message, and passes the sender's name in the shift-click event of the
 * message's style under the CHANGE_PAGE action. The published release has
 * neither half, so a client running it shows chat with the heads missing and
 * nothing to explain why.
 *
 * The quick reply is not part of this. The server's build leaves the chat hit
 * test byte for byte as the release has it, so whatever a click does comes from
 * the click event the server attaches to the message and works either way. What
 * the release loses is the head and the space made for it.
 *
 * Three call-site swaps and one insert, no new branches anywhere, so nothing
 * recomputes a frame:
 *
 * The author is read once per message, at the top of the method that splits a
 * message into lines. The two list insertions in that method then become calls
 * that tag the line they are adding, which is the only moment it is certain
 * which message a line belongs to. They are told apart by the field each one
 * reads, not by their order.
 *
 * In the drawing loop the text call and the background call take the line as an
 * extra argument and decide for themselves. The line is found rather than
 * assumed: it is whatever local the loop stores the list element into.
 *
 * The background is the first of three rectangles the method draws, the other
 * two being the scroll bar.
 */
public final class ChatHeadsTransformer implements IClassTransformer {

    private static final String TARGET_CLASS = "com.llamalad7.betterchat.gui.GuiBetterChat";

    private static final String CHAT_LINE = "net/minecraft/client/gui/ChatLine";
    private static final String LIST = "java/util/List";
    private static final String LIST_DESC = "Ljava/util/List;";
    private static final String ADD_DESC = "(ILjava/lang/Object;)V";

    private static final String FONT_RENDERER = "net/minecraft/client/gui/FontRenderer";
    /** FontRenderer.drawStringWithShadow, as the loader will call it. */
    private static final String DRAW_STRING = "func_175063_a";
    private static final String DRAW_STRING_DESC = "(Ljava/lang/String;FFI)I";

    /** Gui.drawRect, likewise. Inherited, so the owner is the class being patched. */
    private static final String DRAW_RECT = "func_73734_a";
    private static final String DRAW_RECT_DESC = "(IIIII)V";

    private static final String COMPONENT_DESC = "Lnet/minecraft/util/text/ITextComponent;";

    /** The Forge accessor the server's build reads the author through. */
    private static final String SHIFT_CLICK = "getShiftClickEvent";

    private static final String WRAPPED_FIELD = "drawnChatLines";
    private static final String HISTORY_FIELD = "chatLines";

    private static final String OURS = ChatHeads.class.getName().replace('.', '/');
    private static final String LINE = "Ljava/lang/Object;";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || !TARGET_CLASS.equals(transformedName)) {
            return basicClass;
        }

        ClassReader reader = new ClassReader(basicClass);
        ClassNode   node   = new ClassNode();
        reader.accept(node, 0);

        if (alreadyReadsAuthor(node)) {
            return basicClass;
        }

        boolean patched = false;
        for (MethodNode m : node.methods) {
            patched |= tagLines(m);
            patched |= drawHeads(m);
        }

        if (!patched) {
            return basicClass;
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * The method that splits a message into lines, recognised by holding both
     * list insertions rather than by its name.
     */
    private static boolean tagLines(MethodNode m) {
        MethodInsnNode wrapped = null;
        MethodInsnNode history = null;
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (!isListAdd(insn)) {
                continue;
            }
            String field = listBehind(insn);
            if (WRAPPED_FIELD.equals(field)) {
                wrapped = (MethodInsnNode) insn;
            } else if (HISTORY_FIELD.equals(field)) {
                history = (MethodInsnNode) insn;
            }
        }
        if (wrapped == null || history == null) {
            return false;
        }

        int message = componentParameter(m);
        if (message < 0) {
            return false;
        }
        m.instructions.insert(new MethodInsnNode(
            Opcodes.INVOKESTATIC, OURS, "beginMessage", '(' + COMPONENT_DESC + ")V", false));
        m.instructions.insert(new VarInsnNode(Opcodes.ALOAD, message));

        m.instructions.set(wrapped, new MethodInsnNode(
            Opcodes.INVOKESTATIC, OURS, "addWrappedLine", '(' + LIST_DESC + 'I' + LINE + ")V", false));
        m.instructions.set(history, new MethodInsnNode(
            Opcodes.INVOKESTATIC, OURS, "addHistoryLine", '(' + LIST_DESC + 'I' + LINE + ")V", false));
        return true;
    }

    /** The drawing loop: text and background both learn which line they are on. */
    private static boolean drawHeads(MethodNode m) {
        int line = drawnLineLocal(m);
        if (line < 0) {
            return false;
        }

        boolean patched = false;
        boolean background = false;
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode)) {
                continue;
            }
            MethodInsnNode call = (MethodInsnNode) insn;
            if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL
                && FONT_RENDERER.equals(call.owner)
                && DRAW_STRING.equals(call.name)
                && DRAW_STRING_DESC.equals(call.desc)) {
                m.instructions.insertBefore(insn, new VarInsnNode(Opcodes.ALOAD, line));
                m.instructions.set(insn, new MethodInsnNode(
                    Opcodes.INVOKESTATIC, OURS, "drawLine",
                    "(L" + FONT_RENDERER + ";Ljava/lang/String;FFI" + LINE + ")I", false));
                patched = true;
            } else if (!background
                && insn.getOpcode() == Opcodes.INVOKESTATIC
                && DRAW_RECT.equals(call.name)
                && DRAW_RECT_DESC.equals(call.desc)) {
                m.instructions.insertBefore(insn, new VarInsnNode(Opcodes.ALOAD, line));
                m.instructions.set(insn, new MethodInsnNode(
                    Opcodes.INVOKESTATIC, OURS, "drawBackground",
                    "(IIIII" + LINE + ")V", false));
                background = true;
                patched = true;
            }
        }
        return patched;
    }

    /**
     * Whether the class already goes looking for the author itself, which is
     * what the server's own build does. Patching over it would leave two heads
     * on a line and the text pushed aside twice, so a pack shipping that jar is
     * left alone.
     */
    private static boolean alreadyReadsAuthor(ClassNode node) {
        for (MethodNode m : node.methods) {
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn instanceof MethodInsnNode
                    && SHIFT_CLICK.equals(((MethodInsnNode) insn).name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isListAdd(AbstractInsnNode insn) {
        if (insn.getOpcode() != Opcodes.INVOKEINTERFACE) {
            return false;
        }
        MethodInsnNode call = (MethodInsnNode) insn;
        return LIST.equals(call.owner) && "add".equals(call.name) && ADD_DESC.equals(call.desc);
    }

    /** Which list an insertion is going into, read off the field it was loaded from. */
    private static String listBehind(AbstractInsnNode add) {
        for (AbstractInsnNode insn = add.getPrevious(); insn != null; insn = insn.getPrevious()) {
            if (insn.getOpcode() == Opcodes.GETFIELD
                && LIST_DESC.equals(((FieldInsnNode) insn).desc)) {
                return ((FieldInsnNode) insn).name;
            }
        }
        return null;
    }

    /** The message parameter, taken from the descriptor rather than assumed to be first. */
    private static int componentParameter(MethodNode m) {
        org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(m.desc);
        int slot = (m.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (org.objectweb.asm.Type arg : args) {
            if (COMPONENT_DESC.equals(arg.getDescriptor())) {
                return slot;
            }
            slot += arg.getSize();
        }
        return -1;
    }

    /** The local the drawing loop keeps the current line in. */
    private static int drawnLineLocal(MethodNode m) {
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn.getOpcode() != Opcodes.INVOKEINTERFACE) {
                continue;
            }
            MethodInsnNode call = (MethodInsnNode) insn;
            if (!LIST.equals(call.owner) || !"get".equals(call.name)) {
                continue;
            }
            AbstractInsnNode cast = call.getNext();
            if (!(cast instanceof TypeInsnNode) || !CHAT_LINE.equals(((TypeInsnNode) cast).desc)) {
                continue;
            }
            AbstractInsnNode store = cast.getNext();
            if (store instanceof VarInsnNode && store.getOpcode() == Opcodes.ASTORE) {
                return ((VarInsnNode) store).var;
            }
        }
        return -1;
    }
}
