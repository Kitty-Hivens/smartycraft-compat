package dev.hivens.smartycompat.coremod;

import dev.hivens.smartycompat.runtime.HandbagSwapGuard;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Carries the server's two changes to ExtraBotany's handbag container.
 *
 * Pressing a hotbar number while pointing at a slot swaps the two. Point at a
 * slot inside the open handbag and press the number the handbag itself sits on,
 * and the container is asked to move the bag into the inventory the bag is
 * providing. The server's copy refuses that click. It also tells the client
 * about the result of a shift-click, which the published release leaves to
 * whatever sends changes next.
 *
 * Containers are evaluated on both sides, so an unpatched client carries out a
 * swap the server refuses and shows the result until the server corrects it.
 *
 * Two different shapes, so two different techniques:
 *
 * The refusal is a method the published release does not have at all, so it is
 * added whole rather than spliced into anything. Being ours, its one branch and
 * one merge come with the frame written out here, which is not the same risk as
 * asking a transformer to recompute the frames of code somebody else compiled.
 * The condition lives in ordinary Java, and only the early return is bytecode.
 *
 * The notification is a plain insert after the existing take, with no branch
 * and nothing to recompute.
 *
 * Two Minecraft members are named by their runtime spelling here, because an
 * override has to match the name the loader will call and there is nothing in
 * this class to read either from.
 *
 * Both halves are skipped when the class already carries them, so a jar that
 * has the change already (a pack shipping the server's own, or a future release
 * adopting it) is left as it is rather than patched twice.
 */
public final class HandbagSwapTransformer implements IClassTransformer {

    private static final String TARGET_CLASS =
        "com.meteor.extrabotany.client.gui.handbag.ContainerHandbag";

    private static final String CONTAINER = "net/minecraft/inventory/Container";

    /** Container.slotClick, as the loader will call it. */
    private static final String SLOT_CLICK = "func_184996_a";
    private static final String SLOT_CLICK_DESC =
        "(IILnet/minecraft/inventory/ClickType;Lnet/minecraft/entity/player/EntityPlayer;)"
        + "Lnet/minecraft/item/ItemStack;";

    /** Container.detectAndSendChanges, likewise. */
    private static final String SEND_CHANGES = "func_75142_b";

    private static final String SLOT = "net/minecraft/inventory/Slot";
    private static final String ON_TAKE_DESC =
        "(Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/item/ItemStack;)"
        + "Lnet/minecraft/item/ItemStack;";

    private static final String BAG_INVENTORY =
        "com/meteor/extrabotany/client/gui/handbag/InventoryHandbag";
    /**
     * The stack that inventory was opened on. Package-private on the mod's own
     * class, which the container shares a package with, so reading it from here
     * is as legal as the server's copy doing the same.
     */
    private static final String BAG_STACK = "box";
    private static final String STACK_DESC = "Lnet/minecraft/item/ItemStack;";

    private static final String OUR_OWNER = HandbagSwapGuard.class.getName().replace('.', '/');
    private static final String OUR_NAME = "swapsTheBagItself";
    private static final String OUR_DESC =
        "(Lnet/minecraft/inventory/ClickType;ILnet/minecraft/entity/player/EntityPlayer;"
        + "Lnet/minecraft/item/ItemStack;)Z";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || !TARGET_CLASS.equals(transformedName)) {
            return basicClass;
        }

        ClassReader reader = new ClassReader(basicClass);
        ClassNode   node   = new ClassNode();
        reader.accept(node, 0);

        boolean patched = addRefusal(node);
        patched |= sendChangesAfterTransfer(node);

        if (!patched) {
            return basicClass;
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Add the override that refuses a swap moving the bag into itself. Skipped
     * when the class already has one, so this never fights a jar that carries
     * the change already.
     */
    private static boolean addRefusal(ClassNode node) {
        for (MethodNode m : node.methods) {
            if (SLOT_CLICK.equals(m.name) && SLOT_CLICK_DESC.equals(m.desc)) {
                return false;
            }
        }
        FieldInsnNode bag = bagField(node);
        if (bag == null) {
            return false;
        }

        MethodNode m = new MethodNode(
            Opcodes.ASM5, Opcodes.ACC_PUBLIC, SLOT_CLICK, SLOT_CLICK_DESC, null, null);
        LabelNode delegate = new LabelNode();
        InsnList body = m.instructions;

        // if (HandbagSwapGuard.swapsTheBagItself(clickType, dragType, player, bag))
        //     return ItemStack.EMPTY;
        body.add(new VarInsnNode(Opcodes.ALOAD, 3));
        body.add(new VarInsnNode(Opcodes.ILOAD, 2));
        body.add(new VarInsnNode(Opcodes.ALOAD, 4));
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new FieldInsnNode(Opcodes.GETFIELD, bag.owner, bag.name, bag.desc));
        body.add(new FieldInsnNode(Opcodes.GETFIELD, BAG_INVENTORY, BAG_STACK, STACK_DESC));
        body.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC, OUR_OWNER, OUR_NAME, OUR_DESC, false));
        body.add(new JumpInsnNode(Opcodes.IFEQ, delegate));
        body.add(new FieldInsnNode(
            Opcodes.GETSTATIC, "net/minecraft/item/ItemStack", "field_190927_a", STACK_DESC));
        body.add(new InsnNode(Opcodes.ARETURN));

        // the only merge in the method, and the stack is empty at it: the
        // branch above returned rather than joining here
        body.add(delegate);
        body.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

        // return super.slotClick(slotId, dragType, clickType, player);
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new VarInsnNode(Opcodes.ILOAD, 1));
        body.add(new VarInsnNode(Opcodes.ILOAD, 2));
        body.add(new VarInsnNode(Opcodes.ALOAD, 3));
        body.add(new VarInsnNode(Opcodes.ALOAD, 4));
        body.add(new MethodInsnNode(
            Opcodes.INVOKESPECIAL, CONTAINER, SLOT_CLICK, SLOT_CLICK_DESC, false));
        body.add(new InsnNode(Opcodes.ARETURN));

        node.methods.add(m);
        return true;
    }

    /**
     * Tell the client what a shift-click did, right where the server's copy
     * does: after the slot has been taken from and before the method returns.
     */
    private static boolean sendChangesAfterTransfer(ClassNode node) {
        for (MethodNode m : node.methods) {
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode)) {
                    continue;
                }
                MethodInsnNode call = (MethodInsnNode) insn;
                if (!SLOT.equals(call.owner) || !ON_TAKE_DESC.equals(call.desc)) {
                    continue;
                }
                AbstractInsnNode after = call.getNext();
                if (after == null || after.getOpcode() != Opcodes.POP) {
                    continue;
                }
                if (alreadyTells(after.getNext())) {
                    continue;
                }
                InsnList tell = new InsnList();
                tell.add(new VarInsnNode(Opcodes.ALOAD, 0));
                tell.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL, CONTAINER, SEND_CHANGES, "()V", false));
                m.instructions.insert(after, tell);
                return true;
            }
        }
        return false;
    }

    /** Whether the notification is already there, so a jar carrying it is left alone. */
    private static boolean alreadyTells(AbstractInsnNode insn) {
        AbstractInsnNode load = realNext(insn);
        if (load == null || load.getOpcode() != Opcodes.ALOAD
            || ((VarInsnNode) load).var != 0) {
            return false;
        }
        AbstractInsnNode next = realNext(load.getNext());
        if (!(next instanceof MethodInsnNode)) {
            return false;
        }
        MethodInsnNode call = (MethodInsnNode) next;
        return CONTAINER.equals(call.owner) && SEND_CHANGES.equals(call.name);
    }

    /**
     * The next instruction that is one, skipping the labels, line numbers and
     * frames a class compiled with debug information carries between them.
     */
    private static AbstractInsnNode realNext(AbstractInsnNode insn) {
        while (insn != null && insn.getOpcode() < 0) {
            insn = insn.getNext();
        }
        return insn;
    }

    /** The field holding the inventory the bag provides, read off the class itself. */
    private static FieldInsnNode bagField(ClassNode node) {
        for (MethodNode m : node.methods) {
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn instanceof FieldInsnNode
                    && insn.getOpcode() == Opcodes.GETFIELD
                    && ('L' + BAG_INVENTORY + ';').equals(((FieldInsnNode) insn).desc)) {
                    return (FieldInsnNode) insn;
                }
            }
        }
        return null;
    }
}
