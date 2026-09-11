package dev.hivens.smartycompat;

import net.minecraftforge.fml.common.Mod;

/**
 * An empty container. Everything this mod does happens in the coremod
 * transformers, which run long before a @Mod class is constructed; the
 * container exists so the jar passes FML's FMLCorePluginContainsFMLMod
 * scan and so a player can see the mod in the in-game list.
 *
 * clientSideOnly, because this only ever ships in a client pack. Most of
 * the patches are about what a client accepts from a server, and the one
 * that is not (the Railcraft caller lookup) is a fix for the JVM the
 * client runs on. A dedicated server loading this jar would gain nothing.
 * acceptableRemoteVersions = "*" for the same reason: the server does not
 * have it, and the handshake must not ask for it.
 */
@Mod(
    modid                    = "smrtcompat",
    name                     = "SmartyCraft Compat",
    version                  = "${version}",
    acceptableRemoteVersions = "*",
    clientSideOnly           = true
)
public final class SmartyCompat {
}
