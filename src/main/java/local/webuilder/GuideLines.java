package local.webuilder;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;

/** Draw measurement marks on the active world target without touching its depth buffer. */
final class GuideLines extends RenderType {
    static final RenderType TYPE = new GuideLines();
    private GuideLines() {
        super("webuilder_measurement", DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES,
            4096, false, false, () -> {
                RenderSystem.setShader(GameRenderer::getRendertypeLinesShader);
                RenderSystem.disableDepthTest();
                RenderSystem.depthMask(false);
                RenderSystem.disableCull();
                RenderSystem.lineWidth(2.0F);
            }, () -> {
                RenderSystem.lineWidth(1.0F);
                RenderSystem.enableCull();
                RenderSystem.depthMask(true);
                RenderSystem.enableDepthTest();
            });
    }
}
