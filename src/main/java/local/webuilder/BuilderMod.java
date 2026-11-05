package local.webuilder;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

@Mod(BuilderMod.ID)
public final class BuilderMod {
    public static final String ID = "webuilder";
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue HISTORY_BLOCKS;
    public static final ForgeConfigSpec.IntValue CLIPBOARD_BLOCKS;
    public static final ForgeConfigSpec.IntValue MAX_SCAN;
    public static final ForgeConfigSpec.IntValue BATCH;
    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        HISTORY_BLOCKS = b.comment("Beyond this selection volume, explicit confirmation and shared history clearing are required.")
            .defineInRange("historyWarningVolume", 32768, 64, 2000000);
        CLIPBOARD_BLOCKS = b.comment("Clipboard capacity ceiling, also checked against available heap.")
            .defineInRange("clipboardMaxVolume", 1000000, 64, 16000000);
        MAX_SCAN = b.comment("Maximum scan volume per operation; actual capacity checks still apply.")
            .defineInRange("maxScanVolume", 50000000, 64, 1000000000);
        BATCH = b.comment("Maximum visited positions per tick, also limited to about 3 ms.")
            .defineInRange("positionsPerTick", 1024, 16, 8192);
        SPEC = b.build();
    }
    public BuilderMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC);
        Network.init();
        MinecraftForge.EVENT_BUS.register(ServerController.class);
    }
}
