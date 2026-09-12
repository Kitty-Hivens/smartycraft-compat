package dev.hivens.smartycompat.coremod;

import dev.hivens.smartycompat.runtime.PlayerNotice;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Keeps Damage Indicators from dropping the connection while it is being made.
 *
 * On joining, the server tells the client which of the mod's features it
 * allows, and the client answers by writing three lines into chat. It writes
 * them through whatever the proxy hands back for the current player, and it
 * never asks whether that is anything. A mod's channel handler runs on the
 * network thread the moment the packet lands, which is during the handshake and
 * so before the client has built its player, and the send is then made on
 * nothing.
 *
 * That is not a dropped message. An exception out of a channel handler is a
 * fatal packet error to the network dispatcher, which terminates the
 * connection. The client is left holding no world and no player while packets
 * for both keep arriving, the integrated server stops because its only player
 * left, and the loading screen sits at nought per cent with no crash report
 * anywhere, because nothing crashed.
 *
 * It is a race, which is why it looks arbitrary. The first world entered in a
 * session is generated, which takes long enough that the player exists before
 * the packet is handled. A world entered afterwards is already on disk and
 * opens in a few seconds, and the packet wins. A server that answers quickly
 * produces the same race.
 *
 * The six sends are routed through a helper that checks first. Nothing else
 * changes: the flags the same method sets are still set, and a client that does
 * have a player still gets its three lines. A call site swap carries no branch,
 * so no frame in somebody else's method is disturbed.
 */
public final class DamageIndicatorsNoticeTransformer implements IClassTransformer {

    private static final String TARGET_CLASS = "DamageIndicatorsMod.core.DIPermissions$Handler";

    private static final String PLAYER = "net/minecraft/entity/player/EntityPlayer";

    /** EntityPlayer.sendMessage, as the loader will call it. */
    private static final String SEND_MESSAGE = "func_145747_a";
    private static final String SEND_MESSAGE_DESC = "(Lnet/minecraft/util/text/ITextComponent;)V";

    private static final String OUR_OWNER = PlayerNotice.class.getName().replace('.', '/');
    private static final String OUR_NAME = "tell";
    private static final String OUR_DESC =
        "(Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/util/text/ITextComponent;)V";

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
                if (insn.getOpcode() != Opcodes.INVOKEVIRTUAL) {
                    continue;
                }
                MethodInsnNode call = (MethodInsnNode) insn;
                if (!PLAYER.equals(call.owner)
                    || !SEND_MESSAGE.equals(call.name)
                    || !SEND_MESSAGE_DESC.equals(call.desc)) {
                    continue;
                }
                // Same two arguments off the stack, same void result, so the
                // surrounding code neither grows nor shrinks.
                m.instructions.set(call, new MethodInsnNode(
                    Opcodes.INVOKESTATIC, OUR_OWNER, OUR_NAME, OUR_DESC, false));
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
