package dev.hivens.smartycompat.runtime;

import com.mojang.authlib.GameProfile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.event.ClickEvent;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Draws the sender's head beside a chat line, the way the server's own chat
 * build does.
 *
 * The name has nowhere obvious to travel: a chat message arrives as formatted
 * text, and the formatting is the server's own. The server's build sends it
 * outright instead, in the shift-click event of the message's style under the
 * {@code CHANGE_PAGE} action, which nothing in chat otherwise uses. That is read
 * first, because a name the server states is not a guess.
 *
 * The head is only drawn for a name the tab list knows, since the skin comes
 * from there.
 *
 * A server that does not send the name at all is not out of reach: the sender
 * is then read out of the message text, matched against the tab list. That is a
 * guess and is only made when the exact answer is absent, so a server that names
 * the sender outright is always believed over it.
 *
 * A message wraps into several lines and the head belongs to the message, so
 * only the first line carries it. The state between the two is held here rather
 * than on the line, because a transformer cannot give a foreign class a field:
 * the server's build subclasses {@code ChatLine} to hold the author, which is
 * not a shape a call-site rewrite can reach. The map is weak, so a line that
 * scrolled out of the hundred chat keeps takes its entry with it.
 *
 * One deliberate difference from the build this is taken from. That one widens
 * the chat background by the head's width on every line, whether or not a head
 * is there, so a server that sets no author still gets a wider background than
 * vanilla. Here the widening follows the head.
 */
public final class ChatHeads {

    /** Width the head occupies, and so how far the line's text moves right. */
    private static final int HEAD = 10;

    /** Where the skin's face and its hat layer sit in a 64x64 skin. */
    private static final float FACE_U = 8.0F;
    private static final float HAT_U = 40.0F;
    private static final float LAYER_V = 8.0F;
    private static final int LAYER_SIZE = 8;
    private static final float SKIN_SIDE = 64.0F;

    private static final Map<ChatLine, String> AUTHORS = new WeakHashMap<ChatLine, String>();

    /** The author of the message being split, and whether its first line is still to come. */
    private static String pending;
    private static boolean firstLine;

    /** Looked up once: present on a Forge new enough to carry a shift-click event. */
    private static Method accessor;
    private static boolean resolved;

    private ChatHeads() {
    }

    /** Read the author off the message about to be split into lines. */
    public static void beginMessage(ITextComponent message) {
        pending = null;
        firstLine = true;
        if (message == null) {
            return;
        }
        Style style = message.getStyle();
        if (style == null) {
            return;
        }
        ClickEvent event = shiftClickEvent(style);
        if (event != null && event.getAction() == ClickEvent.Action.CHANGE_PAGE) {
            pending = event.getValue();
            return;
        }
        pending = senderInText(message);
    }

    /**
     * The sender read out of the message itself, for a server that sends no name
     * of its own.
     *
     * Only the part before the first `:` or `>` is looked at, which is where every
     * chat format puts the sender and where nothing else goes. That is what keeps
     * a head off "someone joined the game" and off a message that merely mentions
     * a player: neither has a sender segment at all. A prefix the server adds in
     * front, a rank or a channel tag, does not get in the way, since the name only
     * has to appear somewhere in that segment.
     *
     * The longest matching name wins, so a player called Bob does not take the
     * head of a message from Bobby.
     *
     * Guessing from text is the weaker answer and is only reached when the exact
     * one is absent. A server that names the sender outright is always believed
     * over this.
     */
    private static String senderInText(ITextComponent message) {
        String text = message.getUnformattedText();
        if (text == null || text.isEmpty()) {
            return null;
        }
        int colon = text.indexOf(':');
        int angle = text.indexOf('>');
        int end = colon < 0 ? angle : angle < 0 ? colon : Math.min(colon, angle);
        if (end <= 0) {
            return null;
        }
        String sender = text.substring(0, end);

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.player == null || mc.player.connection == null) {
            return null;
        }
        String best = null;
        for (NetworkPlayerInfo info : mc.player.connection.getPlayerInfoMap()) {
            GameProfile profile = info.getGameProfile();
            String name = profile == null ? null : profile.getName();
            if (name == null || name.isEmpty() || !sender.contains(name)) {
                continue;
            }
            if (best == null || name.length() > best.length()) {
                best = name;
            }
        }
        return best;
    }

    /**
     * The style's shift-click event, or null on a platform that has no such
     * thing.
     *
     * Forge added this to Style during 1.12.2 rather than at the start, so a
     * client can be running a build from either side of that. Asked for by name
     * once and remembered, because a hard reference would refuse to load the
     * whole mod on the older side over a feature that only draws a picture.
     */
    private static ClickEvent shiftClickEvent(Style style) {
        if (!resolved) {
            resolved = true;
            try {
                accessor = Style.class.getMethod("getShiftClickEvent");
            } catch (NoSuchMethodException absent) {
                accessor = null;
            }
        }
        if (accessor == null) {
            return null;
        }
        try {
            return (ClickEvent) accessor.invoke(style);
        } catch (ReflectiveOperationException refused) {
            accessor = null;
            return null;
        }
    }

    /**
     * Add a wrapped line, tagging the first one with the author. Replaces the
     * list call itself, so the line is tagged at the only moment it is certain
     * which message it came from.
     */
    public static void addWrappedLine(List<ChatLine> lines, int index, Object line) {
        if (firstLine) {
            tag(line);
        }
        firstLine = false;
        add(lines, index, line);
    }

    /** Add the unsplit line the history keeps, which always carries the author. */
    public static void addHistoryLine(List<ChatLine> lines, int index, Object line) {
        tag(line);
        add(lines, index, line);
    }

    /**
     * Draw the line, moved aside for the head when there is one, then the head
     * over the space that made. Returns what the font renderer returned, so the
     * call site is unchanged in every other respect.
     */
    public static int drawLine(
        FontRenderer font, String text, float x, float y, int colour, Object line) {
        Head head = headFor(line);
        int width = font.drawStringWithShadow(text, head == null ? x : x + HEAD, y, colour);
        if (head != null) {
            draw(head, (int) y);
        }
        return width;
    }

    /** The chat background, widened by the head's width on a line that has one. */
    public static void drawBackground(
        int left, int top, int right, int bottom, int colour, Object line) {
        Gui.drawRect(left, top, headFor(line) == null ? right : right + HEAD, bottom, colour);
    }

    /** The skin and profile behind a line's author, or null when there is no head to draw. */
    private static Head headFor(Object line) {
        if (!(line instanceof ChatLine)) {
            return null;
        }
        String author = AUTHORS.get(line);
        if (author == null) {
            return null;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.player == null || mc.player.connection == null) {
            return null;
        }
        NetHandlerPlayClient connection = mc.player.connection;
        NetworkPlayerInfo info = connection.getPlayerInfo(author);
        if (info == null) {
            return null;
        }
        GameProfile profile = info.getGameProfile();
        return profile == null ? null : new Head(info, profile);
    }

    private static void draw(Head head, int y) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.getTextureManager().bindTexture(head.info.getLocationSkin());
        Gui.drawScaledCustomSizeModalRect(
            0, y, FACE_U, LAYER_V, LAYER_SIZE, LAYER_SIZE, LAYER_SIZE, LAYER_SIZE,
            SKIN_SIDE, SKIN_SIDE);
        if (wearsHat(mc, head.profile)) {
            Gui.drawScaledCustomSizeModalRect(
                0, y, HAT_U, LAYER_V, LAYER_SIZE, LAYER_SIZE, LAYER_SIZE, LAYER_SIZE,
                SKIN_SIDE, SKIN_SIDE);
        }
    }

    /**
     * Whether that player is showing their hat layer. It is a per-player skin
     * setting the client only knows for players it can see, so a sender out of
     * range simply gets the face.
     */
    private static boolean wearsHat(Minecraft mc, GameProfile profile) {
        if (mc.world == null) {
            return false;
        }
        EntityPlayer player = mc.world.getPlayerEntityByUUID(profile.getId());
        return player != null && player.isWearing(EnumPlayerModelParts.HAT);
    }

    private static void tag(Object line) {
        if (pending != null && line instanceof ChatLine) {
            AUTHORS.put((ChatLine) line, pending);
        }
    }

    @SuppressWarnings("unchecked")
    private static void add(List<ChatLine> lines, int index, Object line) {
        ((List<Object>) (List<?>) lines).add(index, line);
    }

    private static final class Head {
        private final NetworkPlayerInfo info;
        private final GameProfile profile;

        private Head(NetworkPlayerInfo info, GameProfile profile) {
            this.info = info;
            this.profile = profile;
        }
    }
}
