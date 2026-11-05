package local.webuilder;

import net.minecraftforge.fml.ModList;
import java.lang.reflect.Method;

/** Optional client compatibility with Effortless Building 3.11, without bundling its implementation. */
final class EffortlessBridge {
    private static Object buildModes;
    private static Object builder;
    private static Object disabled;
    private static Object previous;
    private static Method getMode;
    private static Method setMode;
    private static Method cancel;
    private static boolean initialized;
    private static IllegalStateException initializationFailure;
    static void update(boolean active) {
        if (!initialized) {
            initialized = true;
            if (!ModList.get().isLoaded("effortlessbuilding")) return;
            try {
                Class<?> client = Class.forName("nl.requios.effortlessbuilding.EffortlessBuildingClient");
                Class<?> mode = Class.forName("nl.requios.effortlessbuilding.buildmode.BuildModeEnum");
                buildModes = client.getField("BUILD_MODES").get(null);
                builder = client.getField("BUILDER_CHAIN").get(null);
                disabled = mode.getField("DISABLED").get(null);
                getMode = buildModes.getClass().getMethod("getBuildMode");
                setMode = buildModes.getClass().getMethod("setBuildMode", mode);
                cancel = builder.getClass().getMethod("cancel");
            } catch (ReflectiveOperationException e) {
                buildModes = null;
                initializationFailure = new IllegalStateException("无法暂停当前快速建造版本；请禁用快速建造后使用交互建造。", e);
            }
        }
        if (initializationFailure != null && active) throw initializationFailure;
        if (buildModes == null) return;
        try {
            Object current = getMode.invoke(buildModes);
            if (active) {
                if (previous == null) previous = current;
                if (current != disabled) setMode.invoke(buildModes, disabled);
                cancel.invoke(builder);
            } else if (previous != null) {
                setMode.invoke(buildModes, previous);
                previous = null;
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("暂停快速建造失败。", e);
        }
    }
}
