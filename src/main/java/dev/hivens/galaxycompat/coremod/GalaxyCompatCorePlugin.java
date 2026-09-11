package dev.hivens.galaxycompat.coremod;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import java.util.Map;

/**
 * Entry point named by the jar manifest's FMLCorePlugin attribute. FML
 * resolves it before any @Mod class is constructed and registers the
 * returned transformers with LaunchWrapper, so they apply to every
 * class loaded afterwards -- which is what lets them reach mod classes
 * the pack itself ships.
 *
 * TransformerExclusions keeps this package off the transformation path:
 * a transformer that references its own package can deadlock the class
 * loader by re-entering the pipeline.
 */
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.Name("SmartyCraft Galaxy Compat")
@IFMLLoadingPlugin.TransformerExclusions("dev.hivens.galaxycompat.coremod")
@IFMLLoadingPlugin.SortingIndex(1001)
public final class GalaxyCompatCorePlugin implements IFMLLoadingPlugin {

    @Override
    public String[] getASMTransformerClass() {
        return new String[] {
            "dev.hivens.galaxycompat.coremod.EnderIOVersionCheckTransformer",
            "dev.hivens.galaxycompat.coremod.AdvancedSolarSlotApiTransformer",
            "dev.hivens.galaxycompat.coremod.BatchCrafterSlotFilterTransformer"
        };
    }

    @Override public String getModContainerClass()        { return null; }
    @Override public String getSetupClass()               { return null; }
    @Override public void   injectData(Map<String, Object> data) { }
    @Override public String getAccessTransformerClass()   { return null; }
}
