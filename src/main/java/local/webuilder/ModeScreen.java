package local.webuilder;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Wheel positions are stable so additional geometry can be added without changing existing modes. */
final class ModeScreen extends Screen {
    ModeScreen() { super(Component.literal("交互建造轮盘")); }
    int wheelY() { return Math.min(height / 2, height - 160); }
    @Override protected void init() {
        if (ClientController.status.getString("phase").equals("WARNING")) {
            addRenderableWidget(Button.builder(Component.literal("我已知晓，继续检查范围"), b -> {
                Network.action(5, 0); onClose();
            }).bounds(width / 2 - 110, height / 2 + 50, 220, 20).build());
            addRenderableWidget(Button.builder(Component.literal("取消，保留定位方块及历史"), b -> {
                Network.action(2, 0); onClose();
            }).bounds(width / 2 - 110, height / 2 + 76, 220, 20).build());
            return;
        }
        for (Mode mode : Mode.values()) {
            double angle = -Math.PI / 2 + mode.ordinal() * Math.PI / 4;
            int x = width / 2 + (int) (Math.cos(angle) * Math.min(130, (width - 110) / 2)) - 50;
            int y = wheelY() + (int) (Math.sin(angle) * Math.min(76, wheelY() - 14)) - 10;
            String label = (mode == ClientController.mode() ? "● " : "") + mode.label;
            addRenderableWidget(Button.builder(Component.literal(label), b -> {
                Network.action(0, mode.ordinal()); onClose();
            }).bounds(x, y, 100, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("粘贴包含空气：" + (ClientController.status.getBoolean("air") ? "开" : "关")), b -> {
            Network.action(3, ClientController.status.getBoolean("air") ? 0 : 1); onClose();
        }).bounds(width / 2 - 85, wheelY() + 108, 170, 20).build());
        Button reserved = Button.builder(Component.literal("更多几何体 · 预留"), b -> {}).bounds(width / 2 - 85, wheelY() + 134, 170, 20).build();
        reserved.active = false;
        addRenderableWidget(reserved);
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        if (ClientController.status.getString("phase").equals("WARNING")) {
            graphics.drawCenteredString(font, "大范围操作确认", width / 2, height / 2 - 72, 0xFFCC66);
            graphics.drawCenteredString(font, "范围 " + ClientController.status.getLong("volume") + " 个位置；采用简化预览", width / 2, height / 2 - 50, 0xFFFFFF);
            if (ClientController.status.getBoolean("noHistory")) {
                graphics.drawCenteredString(font, "此操作无法撤销！执行前将清空你的全部共享历史", width / 2, height / 2 - 27, 0xFF7777);
                graphics.drawCenteredString(font, "包含手动 WorldEdit 操作、恢复记录及其他维度历史", width / 2, height / 2 - 10, 0xFFBBBB);
                graphics.drawCenteredString(font, "中止只停止后续修改，已完成部分保留", width / 2, height / 2 + 7, 0xFFBBBB);
            } else {
                graphics.drawCenteredString(font, "复制仍受剪贴板容量限制；本操作不会清空历史", width / 2, height / 2 - 15, 0xFFFFFF);
            }
            graphics.drawCenteredString(font, "检查完成后还需右键确认执行", width / 2, height / 2 + 28, 0xFFFFFF);
        } else {
            graphics.drawCenteredString(font, "交互建造", width / 2, wheelY() - 10, 0xFFE6A3);
            graphics.drawCenteredString(font, "选择模式", width / 2, wheelY() + 6, 0xFFFFFF);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (ClientController.MENU.matches(key, scan)) { onClose(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public void onClose() {
        ClientController.clickCooldown = 8;
        ClientController.waitingForRelease = true;
        super.onClose();
    }
    @Override public boolean isPauseScreen() { return false; }
}
