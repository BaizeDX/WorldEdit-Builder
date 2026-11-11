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
    static final PreviewMesh mesh = new PreviewMesh();
    static final java.util.List<MeasureGuide> locked = new java.util.ArrayList<>();
    static MeasureGuide pending;
    static boolean guideHeld;
    static int clickCooldown;
    static boolean waitingForRelease;
    public static void receive(CompoundTag data) {
        CompoundTag next = data == null ? new CompoundTag() : data;
        if (next.contains("guideDimension")) {
            pending = new MeasureGuide(next.getString("guideDimension"), BlockPos.of(next.getLong("start")), BlockPos.of(next.getLong("end")));
            return;
        }
        if (!status.getString("phase").equals(next.getString("phase")) ||
                status.getInt("mode") != next.getInt("mode")) waitingForRelease = true;
        if (!status.equals(next)) mesh.clear();
        status = next;
        if (Minecraft.getInstance().screen instanceof ModeScreen menu) menu.syncOptions();
    }
    static Mode mode() {
        int n = status.getInt("mode");
        return n >= 0 && n < Mode.values().length ? Mode.values()[n] : Mode.NONE;
    }
    @Mod.EventBusSubscriber(modid = BuilderMod.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent public static void reload(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener)
                manager -> Minecraft.getInstance().execute(mesh::clear));
        }
        @SubscribeEvent public static void keys(RegisterKeyMappingsEvent e) { e.register(MENU); }
    }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (clickCooldown > 0) clickCooldown--;
        Minecraft mc = Minecraft.getInstance();
        if (!mc.options.keyUse.isDown()) guideHeld = false;
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
    @SubscribeEvent public static void guideClick(net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickEmpty event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND || event.getEntity() != mc.player
            || !mc.player.getMainHandItem().isEmpty() || guideHeld
            || !status.getString("phase").isEmpty() || (mode() == Mode.CURVE && mc.player.isShiftKeyDown())) return;
        String dimension = mc.level.dimension().location().toString();
        MeasureGuide target = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (MeasureGuide guide : locked) {
            if (!guide.dimension().equals(dimension)) continue;
            double hit = guide.hitDistance(mc.player.getEyePosition(), mc.player.getLookAngle());
            if (hit < nearest) { nearest = hit; target = guide; }
        }
        String feedback;
        if (target != null) { locked.remove(target); feedback = "已移除辅助线"; }
        else if (pending == null || !dimension.equals(pending.dimension())) return;
        else if (locked.size() == 10) feedback = "辅助线已满（10 条）";
        else { locked.add(pending); pending = null; feedback = "辅助线已锁定"; }
        guideHeld = true;
        mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(feedback), true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void interact(InputEvent.InteractionKeyMappingTriggered e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || (!e.isAttack() && !e.isUseItem())) return;
        boolean finishCurve = mode() == Mode.CURVE && status.getString("phase").isEmpty() && e.isUseItem()
            && mc.player.isShiftKeyDown() && mc.player.getMainHandItem().isEmpty();
        if (!finishCurve && !waitingForRelease && (mode() == Mode.NONE || status.getString("phase").isEmpty())) return;
        e.setCanceled(true);
        e.setSwingHand(false);
        if (clickCooldown > 0 || waitingForRelease) return;
        clickCooldown = 5;
        waitingForRelease = true;
        if (finishCurve) {
            Network.action(8, 0);
        } else if (e.isAttack()) {
            Network.action(2, 0);
        } else {
            String phase = status.getString("phase");
            if (phase.equals("READY")) Network.action(1, 0);

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
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        BlockPos low = BlockPos.of(status.getLong("min"));
        BlockPos high = BlockPos.of(status.getLong("max"));
        PoseStack pose = e.getPoseStack();
        var camera = e.getCamera().getPosition();
        var buffers = mc.renderBuffers().bufferSource();
        String dim = mc.level.dimension().location().toString();
        boolean visible = pending != null && pending.dimension().equals(dim);
        if (visible) drawGuide(pending, pose, camera, buffers);
        for (MeasureGuide guide : locked) {
            if (guide.dimension().equals(dim)) { drawGuide(guide, pose, camera, buffers); visible = true; }
        }
        if (visible) buffers.endBatch(RenderType.lines());
        if (!status.getString("phase").equals("READY") || mode() == Mode.NONE) return;
        mesh.draw(status, pose, camera);
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
                LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()), new AABB(pos).inflate(0.003), 0.2f, 1, 0.6f, 0.8f);
            }
        }
        for (long location : status.getLongArray("highlights")) {
            LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()), new AABB(BlockPos.of(location)).inflate(0.03), 1, 0.22f, 0.8f, 1);
        }
        pose.popPose();

        buffers.endBatch(RenderType.lines());
    }
    private static void drawGuide(MeasureGuide guide, PoseStack pose, net.minecraft.world.phys.Vec3 camera,
                                  net.minecraft.client.renderer.MultiBufferSource.BufferSource buffers) {
            var start = net.minecraft.world.phys.Vec3.atCenterOf(guide.a());
            var end = net.minecraft.world.phys.Vec3.atCenterOf(guide.b());
            var lines = buffers.getBuffer(RenderType.lines());
            pose.pushPose();
            pose.translate(start.x - camera.x, start.y - camera.y, start.z - camera.z);
            var delta = end.subtract(start);
            var normal = delta.normalize();
            var transform = pose.last();
            if (delta.lengthSqr() > 0) {
                lines.vertex(transform.pose(), 0, 0, 0).color(255, 255, 255, 255)
                    .normal(transform.normal(), (float) normal.x, (float) normal.y, (float) normal.z).endVertex();
                lines.vertex(transform.pose(), (float) delta.x, (float) delta.y, (float) delta.z).color(255, 255, 255, 255)
                    .normal(transform.normal(), (float) normal.x, (float) normal.y, (float) normal.z).endVertex();
            }
            for (var marker : guide.markers().entrySet()) {
                var box = new AABB(marker.getKey()).move(-start.x, -start.y, -start.z).inflate(0.003);
                boolean gold = marker.getValue();
                LevelRenderer.renderLineBox(pose, lines, box, gold ? 1 : 0, gold ? 0.75f : 1, gold ? 0 : 1, 1);
            }
            pose.popPose();
    }
    @SubscribeEvent public static void unload(net.minecraftforge.event.level.LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            Minecraft.getInstance().execute(() -> { mesh.clear(); status = new CompoundTag(); });
        }
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut e) {
        try { EffortlessBridge.update(false); } catch (IllegalStateException ignored) { }
        mesh.clear();
        locked.clear(); pending = null; guideHeld = false;
        status = new CompoundTag(); clickCooldown = 0; waitingForRelease = false;
    }
}
