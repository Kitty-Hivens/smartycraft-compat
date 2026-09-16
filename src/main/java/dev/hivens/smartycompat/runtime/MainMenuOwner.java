package dev.hivens.smartycompat.runtime;

import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;

/**
 * Which mod owns the main menu when two of them want it.
 *
 * The Aether's menu is named rather than referenced. Both halves of this are
 * called from packs that do not necessarily ship the Aether, and a direct
 * reference would resolve to nothing there.
 */
public final class MainMenuOwner {

    private static final String AETHER_MENU =
        "com.gildedgames.the_aether.client.gui.menu.AetherMainMenu";

    private MainMenuOwner() {
    }

    /**
     * Whether the Aether should put its own menu in place of this screen: any
     * main menu, including another mod's, but never the one it would install.
     * That last part is what keeps the replacement from repeating on the screen
     * it just opened.
     */
    public static boolean aetherShouldTakeOver(GuiScreen gui) {
        return gui instanceof GuiMainMenu && !isAetherMenu(gui);
    }

    /** Whether this screen is the Aether's own menu. */
    public static boolean isAetherMenu(GuiScreen gui) {
        return gui != null && AETHER_MENU.equals(gui.getClass().getName());
    }
}
