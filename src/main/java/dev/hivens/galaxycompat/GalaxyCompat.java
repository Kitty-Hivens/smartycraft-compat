package dev.hivens.galaxycompat;

import net.minecraftforge.fml.common.Mod;

/**
 * An empty container. Everything this mod does happens in the coremod
 * transformers, which run long before a @Mod class is constructed; the
 * container exists so the jar passes FML's FMLCorePluginContainsFMLMod
 * scan and so a player can see the mod in the in-game list.
 *
 * clientSideOnly, because every patch here is about what a client
 * accepts from a server. A dedicated server loading this jar would
 * gain nothing. acceptableRemoteVersions = "*" for the same reason:
 * the server does not have it, and the handshake must not ask for it.
 */
@Mod(
    modid                    = "smrtgalaxycompat",
    name                     = "SmartyCraft Galaxy Compat",
    version                  = "${version}",
    acceptableRemoteVersions = "*",
    clientSideOnly           = true
)
public final class GalaxyCompat {
}
