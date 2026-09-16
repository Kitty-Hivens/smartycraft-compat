package dev.hivens.smartycompat.coremod;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import java.util.Map;

/**
 * Entry point named by the jar manifest's FMLCorePlugin attribute. FML
 * resolves it before any @Mod class is constructed and registers the
 * returned transformers with LaunchWrapper, so they apply to every
 * class loaded afterwards -- which is what lets them reach mod classes
 * the pack itself ships.
 *
 * TransformerExclusions keeps this mod's whole package tree off the
 * transformation path: a transformer that references its own package can
 * deadlock the class loader by re-entering the pipeline. The exclusion
 * skips transformation only, so these classes are still loaded by the
 * game's own class loader and can therefore resolve mod classes by name.
 */
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.Name("SmartyCraft Compat")
@IFMLLoadingPlugin.TransformerExclusions("dev.hivens.smartycompat")
@IFMLLoadingPlugin.SortingIndex(1001)
public final class SmartyCompatCorePlugin implements IFMLLoadingPlugin {

    @Override
    public String[] getASMTransformerClass() {
        return new String[] {
            "dev.hivens.smartycompat.coremod.EnderIOVersionCheckTransformer",
            "dev.hivens.smartycompat.coremod.Ic2SlotHolderTransformer",
            "dev.hivens.smartycompat.coremod.SemiFluidFuelWidenTransformer",
            "dev.hivens.smartycompat.coremod.BatchCrafterSlotFilterTransformer",
            "dev.hivens.smartycompat.coremod.InscriberMatchTransformer",
            "dev.hivens.smartycompat.coremod.CacheOffHandTransformer",
            "dev.hivens.smartycompat.coremod.UncraftingTakeTransformer",
            "dev.hivens.smartycompat.coremod.HandMirrorOffHandTransformer",
            "dev.hivens.smartycompat.coremod.HandbagSwapTransformer",
            "dev.hivens.smartycompat.coremod.ChatHeadsTransformer",
            "dev.hivens.smartycompat.coremod.DamageIndicatorsNoticeTransformer",
            "dev.hivens.smartycompat.coremod.RailcraftCallerClassTransformer",
            "dev.hivens.smartycompat.coremod.AetherTexturePackPathTransformer",
            "dev.hivens.smartycompat.coremod.AetherMenuTakeoverTransformer",
            "dev.hivens.smartycompat.coremod.BetweenlandsMainMenuTransformer",
            "dev.hivens.smartycompat.coremod.RatsPlagueDoctorTransformer"
        };
    }

    @Override public String getModContainerClass()        { return null; }
    @Override public String getSetupClass()               { return null; }
    @Override public void   injectData(Map<String, Object> data) { }
    @Override public String getAccessTransformerClass()   { return null; }
}
