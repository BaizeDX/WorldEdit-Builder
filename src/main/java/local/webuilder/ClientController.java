package local.webuilder;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.*;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = BuilderMod.ID, value = Dist.CLIENT)
public final class ClientController {
    static final KeyMapping MENU = new KeyMapping("key.webuilder.menu", InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_GRAVE_ACCENT, "key.categories.webuilder");
    static CompoundTag status = new CompoundTag();
    static int clickCooldown;
    static boolean waitingForRelease;
    public static void receive(CompoundTag data) { status = data == null ? new CompoundTag() : data; }
    static Mode mode() {
        int n = status.getInt("mode");
        return n >= 0 && n < Mode.values().length ? Mode.values()[n] : Mode.NONE;
    }
    @Mod.EventBusSubscriber(modid = BuilderMod.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent public static void keys(RegisterKeyMappingsEvent e) { e.register(MENU); }
    }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (clickCooldown > 0) clickCooldown--;
        Minecraft mc = Minecraft.getInstance();
        long window = mc.getWindow().getWindow();
        if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_RELEASE
            && GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_RELEASE) {
            waitingForRelease = false;
        }
        if (mc.player != null) {
            try { EffortlessBridge.update(mode() != Mode.NONE); }
            catch (IllegalStateException ex) {
                if (mode() != Mode.NONE) {
                    mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(ex.getMessage()), false);
                    Network.action(0, 0);
                    status = new CompoundTag();
                }
            }
        }
        while (MENU.consumeClick()) {
            if (mc.player != null && mc.screen == null) mc.setScreen(new ModeScreen());
        }
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void interact(InputEvent.InteractionKeyMappingTriggered e) {
        Minecraft mc = Minecraft.getInstance();
        if (mode() == Mode.NONE || mc.player == null || mc.screen != null || (!e.isAttack() && !e.isUseItem())) return;
        e.setCanceled(true);
        e.setSwingHand(false);
        if (clickCooldown > 0 || waitingForRelease) return;
        clickCooldown = 5;
        waitingForRelease = true;
        if (e.isAttack()) {
            Network.action(2, 0);
        } else {
            String phase = status.getString("phase");
            if (phase.equals("READY")) Network.action(1, 0);
            else if (phase.isEmpty()) Network.action(4, 0);
        }
    }
    @SubscribeEvent public static void hud(RenderGuiEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (mode() == Mode.NONE || mc.player == null || mc.options.hideGui) return;
        String text = mode().label + " · " + status.getString("stage");
        int x = (mc.getWindow().getGuiScaledWidth() - mc.font.width(text)) / 2;
        int y = mc.getWindow().getGuiScaledHeight() - 64;
        e.getGuiGraphics().fill(x - 4, y - 3, x + mc.font.width(text) + 4, y + 11, 0x99000000);
        e.getGuiGraphics().drawString(mc.font, text, x, y, 0xFFE6A3, true);
    }
    @SubscribeEvent public static void render(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || !status.getString("phase").equals("READY")) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mode() == Mode.NONE) return;
        BlockPos low = BlockPos.of(status.getLong("min"));
        BlockPos high = BlockPos.of(status.getLong("max"));
        PoseStack pose = e.getPoseStack();
        var camera = e.getCamera().getPosition();
        var buffers = mc.renderBuffers().bufferSource();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()),
            new AABB(low.getX(), low.getY(), low.getZ(), high.getX() + 1, high.getY() + 1, high.getZ() + 1), 1, 0.8f, 0.2f, 1);
        for (Tag tag : status.getList("preview", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) tag;
            BlockPos pos = BlockPos.of(entry.getLong("pos"));
            var state = Block.stateById(entry.getInt("state"));
            if (pos.distToCenterSqr(camera.x, camera.y, camera.z) > 128 * 128) continue;
            if (state.isAir()) {
                LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()), new AABB(pos).inflate(0.002), 1, 0.15f, 0.15f, 0.9f);
            } else {
                if (state.getRenderShape() == net.minecraft.world.level.block.RenderShape.MODEL) {
                    pose.pushPose();
                    pose.translate(pos.getX() + 0.05, pos.getY() + 0.05, pos.getZ() + 0.05);
                    pose.scale(0.9f, 0.9f, 0.9f);
                    mc.getBlockRenderer().renderSingleBlock(state, pose, buffers, 15728880,
                        OverlayTexture.NO_OVERLAY, ModelData.EMPTY, RenderType.translucent());
                    pose.popPose();
                }
                LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()), new AABB(pos).inflate(0.003), 0.2f, 1, 0.6f, 0.8f);
            }
        }
        pose.popPose();
        buffers.endBatch(RenderType.translucent());
        buffers.endBatch(RenderType.lines());
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut e) {
        try { EffortlessBridge.update(false); } catch (IllegalStateException ignored) { }
        status = new CompoundTag(); clickCooldown = 0; waitingForRelease = false;
    }
}
