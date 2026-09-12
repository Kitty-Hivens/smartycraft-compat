package dev.hivens.smartycompat.runtime;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.text.ITextComponent;

/**
 * Says something to a player, or says nothing when there is no player to say it
 * to.
 *
 * A mod that answers a packet by writing into chat is written as though the
 * client it is talking to already has a player. On a connection that is still
 * shaking hands it does not, and the difference decides whether the player gets
 * into the world at all: a mod's channel handler runs on the network thread,
 * and an exception thrown there is a fatal packet error, on which the whole
 * connection is dropped.
 */
public final class PlayerNotice {

    private PlayerNotice() {
    }

    public static void tell(EntityPlayer player, ITextComponent message) {
        if (player != null) {
            player.sendMessage(message);
        }
    }
}
