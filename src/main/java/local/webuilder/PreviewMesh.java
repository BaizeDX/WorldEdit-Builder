package local.webuilder;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import java.util.LinkedHashMap;
import java.util.Map;

/** GPU geometry in preview-local coordinates, owned by the client render thread. */
final class PreviewMesh {
    private final Map<RenderType, VertexBuffer> layers = new LinkedHashMap<>();
    private boolean built;
    private BlockPos origin = BlockPos.ZERO;

    void clear() {
        layers.values().forEach(VertexBuffer::close);
        layers.clear();
        built = false;
    }

    private void build(CompoundTag preview) {
        origin = BlockPos.of(preview.getLong("min"));
        Map<RenderType, BufferBuilder> builders = new LinkedHashMap<>();
        MultiBufferSource output = layer -> builders.computeIfAbsent(layer, key -> {
            var buffer = new BufferBuilder(4096);
            buffer.begin(key.mode(), key.format());
            return buffer;
        });
        var renderer = Minecraft.getInstance().getBlockRenderer();
        PoseStack local = new PoseStack();
        for (Tag tag : preview.getList("preview", Tag.TAG_COMPOUND)) {
            CompoundTag cell = (CompoundTag) tag;
            var state = Block.stateById(cell.getInt("state"));
            if (state.getRenderShape() != RenderShape.MODEL) continue;
            var offset = BlockPos.of(cell.getLong("pos")).subtract(origin);
            local.pushPose();
            local.translate(offset.getX() + .05, offset.getY() + .05, offset.getZ() + .05);
            local.scale(.9f, .9f, .9f);
            renderer.renderSingleBlock(state, local, output, 15728880, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
            local.popPose();
        }
        int bytes = 0;
        try {
            for (var entry : builders.entrySet()) {
                var data = entry.getValue().endOrDiscardIfEmpty();
                if (data == null) continue;
                bytes += data.vertexBuffer().remaining();
                if (bytes > 16 * 1024 * 1024) { data.release(); continue; }
                var mesh = new VertexBuffer(VertexBuffer.Usage.STATIC);
                layers.put(entry.getKey(), mesh);
                mesh.bind();
                mesh.upload(data);
            }
        } finally { VertexBuffer.unbind(); }
        built = true;
    }

    void draw(CompoundTag preview, PoseStack worldPose, net.minecraft.world.phys.Vec3 camera) {
        if (!built) {
            try { build(preview); }
            catch (RuntimeException ex) {
                clear();
                built = true;
                com.mojang.logging.LogUtils.getLogger().warn("Preview model failed; showing outlines for this selection", ex);
            }
        }
        Matrix4f view = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(worldPose.last().pose())
            .translate((float)(origin.getX() - camera.x), (float)(origin.getY() - camera.y), (float)(origin.getZ() - camera.z));
        for (var entry : layers.entrySet()) {
            RenderType layer = entry.getKey();
            layer.setupRenderState();
            try {
                entry.getValue().bind();
                entry.getValue().drawWithShader(view, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
            } finally {
                VertexBuffer.unbind();
                layer.clearRenderState();
            }
        }
    }
}
