package dev.hivens.smartycompat.coremod;

import dev.hivens.smartycompat.runtime.HandMirrorSlot;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Carries the server's off hand fallback for Thaumcraft's hand mirror.
 *
 * The container takes whatever sits in the selected hotbar slot and calls it
 * the mirror. Open the mirror from the off hand and the container is built
 * around the main hand's item, so the client shows a container that has
 * nothing to do with what the player used. The server's copy looks in the off
 * hand when the main hand is not holding one.
 *
 * Containers are built on both sides, so this is the client's own view being
 * wrong, not a disagreement the server corrects.
 *
 * The rewrite inserts straight after the constructor stores what it read from
 * the selected slot, and is branchless: the field, the inventory and the mod's
 * own mirror class go on the stack and the decision is made in ordinary Java.
 * No new jump targets, so no stack map frame is recomputed.
 *
 * Nothing is named by a mapping here. The store is found by following the only
 * no-argument `InventoryPlayer` call returning an ItemStack, the field by the
 * store it feeds, and the mirror class by the call the class already makes into
 * it, so the patch does not depend on how Minecraft's members are spelled in
 * the jar it meets.
 */
public final class HandMirrorOffHandTransformer implements IClassTransformer {

    private static final String TARGET_CLASS = "thaumcraft.common.container.ContainerHandMirror";

    private static final String INVENTORY_OWNER = "net/minecraft/entity/player/InventoryPlayer";
    private static final String READS_STACK_DESC = "()Lnet/minecraft/item/ItemStack;";
    private static final String STACK_DESC = "Lnet/minecraft/item/ItemStack;";

    private static final String MIRROR_ITEM = "ItemHandMirror";

    private static final String OUR_OWNER = HandMirrorSlot.class.getName().replace('.', '/');
    private static final String OUR_NAME = "resolve";
    private static final String OUR_DESC =
        "(Lnet/minecraft/item/ItemStack;Lnet/minecraft/entity/player/InventoryPlayer;"
        + "Ljava/lang/Class;)Lnet/minecraft/item/ItemStack;";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || !TARGET_CLASS.equals(transformedName)) {
            return basicClass;
        }

        ClassReader reader = new ClassReader(basicClass);
        ClassNode   node   = new ClassNode();
        reader.accept(node, 0);

        String mirrorType = mirrorItemOwner(node);
        if (mirrorType == null) {
            return basicClass;
        }

        boolean patched = false;
        for (MethodNode m : node.methods) {
            if (!"<init>".equals(m.name)) {
                continue;
            }
            FieldInsnNode store = mirrorStore(m);
            if (store == null) {
                continue;
            }

            InsnList pick = new InsnList();
            pick.add(new VarInsnNode(Opcodes.ALOAD, 0));
            pick.add(new InsnNode(Opcodes.DUP));
            pick.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, store.name, STACK_DESC));
            pick.add(new VarInsnNode(Opcodes.ALOAD, 1));
            pick.add(new LdcInsnNode(Type.getObjectType(mirrorType)));
            pick.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, OUR_OWNER, OUR_NAME, OUR_DESC, false));
            pick.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, store.name, STACK_DESC));

            m.instructions.insert(store, pick);
            patched = true;
            break;
        }

        if (!patched) {
            return basicClass;
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * The field the constructor puts the selected slot's stack into: the store
     * that follows the container's only read of one off the player inventory.
     */
    private static FieldInsnNode mirrorStore(MethodNode m) {
        boolean read = false;
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) insn;
                read = INVENTORY_OWNER.equals(call.owner) && READS_STACK_DESC.equals(call.desc);
                continue;
            }
            if (read
                && insn.getOpcode() == Opcodes.PUTFIELD
                && STACK_DESC.equals(((FieldInsnNode) insn).desc)) {
                return (FieldInsnNode) insn;
            }
        }
        return null;
    }

    /** The mod's own mirror item class, taken from a call the class already makes into it. */
    private static String mirrorItemOwner(ClassNode node) {
        for (MethodNode m : node.methods) {
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn instanceof MethodInsnNode) {
                    String owner = ((MethodInsnNode) insn).owner;
                    if (owner.endsWith('/' + MIRROR_ITEM)) {
                        return owner;
                    }
                }
            }
        }
        return null;
    }
}
