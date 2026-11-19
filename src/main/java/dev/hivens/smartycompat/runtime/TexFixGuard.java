package dev.hivens.smartycompat.runtime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.ResourceLocation;

import java.util.LinkedList;

/**
 * What TexFix calls once {@code TexFixLoadGuardTransformer} has rewritten it.
 *
 * Only Minecraft's own types and the JDK appear here: a pack without TexFix
 * never reaches this class, and one with it hands in plain objects.
 */
public final class TexFixGuard {

    private TexFixGuard() {
    }

    /**
     * Whether TexFix should leave this sprite's frames in memory. It already
     * left animated sprites alone. A sprite with a custom loader is left alone
     * too, because reloading it means calling that loader again, and the
     * loader may need the GL context that only the stitch on the render thread
     * has (Railcraft's registers a texture).
     *
     * The location is built the way TexFix builds it for its own reload, so
     * the loader is asked about the same file it would later be called on.
     * Anything that goes wrong in asking answers as TexFix would have.
     */
    public static boolean leaveAlone(TextureAtlasSprite sprite) {
        if (sprite.hasAnimationMetadata()) {
            return true;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            TextureMap map = mc.getTextureMapBlocks();
            if (map == null) {
                return false;
            }
            ResourceLocation name = new ResourceLocation(sprite.getIconName());
            ResourceLocation file = new ResourceLocation(name.getResourceDomain(),
                String.format("%s/%s%s", map.getBasePath(), name.getResourcePath(), ".png"));
            return sprite.hasCustomLoader(mc.getResourceManager(), file);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // TexFix's queue of sprites to free again. A reload appends to it from
    // whichever thread asked for the frames and the client tick drains it, so
    // every call holds the list's monitor. Only the tick removes, so its
    // size-then-first pair stays sound between the two calls.

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean add(LinkedList list, Object entry) {
        synchronized (list) {
            return list.add(entry);
        }
    }

    @SuppressWarnings("rawtypes")
    public static int size(LinkedList list) {
        synchronized (list) {
            return list.size();
        }
    }

    @SuppressWarnings("rawtypes")
    public static Object getFirst(LinkedList list) {
        synchronized (list) {
            return list.getFirst();
        }
    }

    @SuppressWarnings("rawtypes")
    public static Object removeFirst(LinkedList list) {
        synchronized (list) {
            return list.removeFirst();
        }
    }
}
