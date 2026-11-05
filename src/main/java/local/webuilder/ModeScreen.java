package local.webuilder;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Wheel positions are stable so additional geometry can be added without changing existing modes. */
final class ModeScreen extends Screen {
    ModeScreen() { super(Component.literal("交互建造菜单")); }
    private Button air, continuous, geometryAir;
    private int top() { return Math.max(5, (height - 205) / 2); }
    private int colWidth() { return Math.min(130, (width - 20) / 3); }
    private int left(int col) { return (width - colWidth() * 3) / 2 + col * colWidth(); }
    void syncOptions() {
        if (geometryAir != null) {
            geometryAir.setMessage(Component.literal("几何体仅替换空气：" + (ClientController.status.getBoolean("geometryAirOnly") ? "开" : "关")));
            geometryAir.active = ClientController.status.getString("phase").isEmpty();
        }
        if (air != null) air.setMessage(Component.literal("粘贴包含空气：" + (ClientController.status.getBoolean("air") ? "开" : "关")));
        if (continuous != null) continuous.setMessage(Component.literal("连续模式：" + (ClientController.status.getBoolean("continuous") ? "开" : "关")));
    }
    private Button button(int col, int row, String name, Button.OnPress action) {
        return addRenderableWidget(Button.builder(Component.literal(name), action)
            .bounds(left(col) + 2, top() + 46 + 23 * row, colWidth() - 4, 20).build());
    }
    private void mode(int col, int row, Mode mode) {
        button(col, row, (mode == ClientController.mode() ? "● " : "") + mode.label, b -> {
            Network.action(0, mode.ordinal()); onClose();
        });
    }
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
        mode(0, 0, Mode.SOLID); mode(0, 1, Mode.HOLLOW); mode(0, 2, Mode.FRAME);
        button(0, 3, "更多形状（预留）", b -> {}).active = false;
        mode(1, 0, Mode.COPY); mode(1, 1, Mode.CUT); mode(1, 2, Mode.PASTE);
        mode(1, 3, Mode.REPLACE); mode(1, 4, Mode.CLEAR);
        mode(2, 0, Mode.NONE);
        continuous = button(2, 1, "", b -> Network.action(6, ClientController.status.getBoolean("continuous") ? 0 : 1));
        air = button(2, 2, "", b -> Network.action(3, ClientController.status.getBoolean("air") ? 0 : 1));
        geometryAir = button(2, 3, "", b -> Network.action(7, ClientController.status.getBoolean("geometryAirOnly") ? 0 : 1));
        syncOptions();
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
            graphics.drawCenteredString(font, "交互建造", width / 2, top() + 4, 0xFFE6A3);
            String[] labels = {"建造形状", "编辑操作", "模式设置"};
            for (int i = 0; i < labels.length; i++)
                graphics.drawCenteredString(font, labels[i], left(i) + colWidth() / 2, top() + 30, 0xFFFFFF);
            graphics.drawCenteredString(font, "放置方块选点 · 粘贴只需一个定位方块", width / 2, top() + 171, 0xDDDDDD);
            graphics.drawCenteredString(font, "预览后右键确认，左键取消", width / 2, top() + 187, 0xDDDDDD);
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
