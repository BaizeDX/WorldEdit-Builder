package local.webuilder;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import net.minecraft.client.gui.components.Tooltip;
final class ModeScreen extends Screen {
    ModeScreen() { super(Component.literal("交互建造菜单")); }
    private enum Page { MAIN, GEOMETRY, SPHERE, TRIANGLE, CYLINDER }
    private Page page = Page.MAIN;
    private static Mode sphere = Mode.SPHERE, triangle = Mode.TRIANGLE, cylinder = Mode.CYLINDER;
    private Button air, continuous, geometryAir, overwrite;
    private int top() { return Math.max(5, (height - 205) / 2); }
    private int colWidth() { return Math.min(130, (width - 20) / 3); }
    private int left(int col) { return (width - colWidth() * 3) / 2 + col * colWidth(); }
    void syncOptions() {
        if (overwrite != null) {
            overwrite.setMessage(Component.literal("覆盖目标：" + (ClientController.status.getBoolean("overwrite") ? "开" : "关")));
            overwrite.active = ClientController.status.getString("phase").isEmpty();
        }
        if (geometryAir != null) {
            geometryAir.setMessage(Component.literal("几何体仅替换空气：" + (ClientController.status.getBoolean("geometryAirOnly") ? "开" : "关")));
            geometryAir.active = ClientController.status.getString("phase").isEmpty();
        }
        if (air != null) air.setMessage(Component.literal("粘贴包含空气：" + (ClientController.status.getBoolean("air") ? "开" : "关")));
        if (continuous != null) continuous.setMessage(Component.literal("连续模式：" + (ClientController.status.getBoolean("continuous") ? "开" : "关")));
    }
    private Button button(int col, int row, String name, Button.OnPress action) {
        return addRenderableWidget(Button.builder(Component.literal(name), action)
            .bounds(left(col) + 2, top() + 35 + 18 * row, colWidth() - 4, 17).build());
    }
    private void mode(int col, int row, Mode mode) {
        button(col, row, (mode == ClientController.mode() ? "● " : "") + mode.label, b -> {
            Network.action(0, mode.ordinal()); onClose();
        });
    }
    private void show(Page next) {
        page = next;
        rebuildWidgets();
    }
    private int panelWidth() { return Math.min(206, width - 16); }
    private Button row(int index, String label, int reserve, Button.OnPress action) {
        int w = panelWidth();
        return addRenderableWidget(Button.builder(Component.literal(label), action)
            .bounds((width - w) / 2, top() + 28 + index * 22, w - reserve, 20).build());
    }
    private void shape(int index, Mode mode, Page settings) {
        row(index, (mode == ClientController.mode() ? "● " : "") + mode.label, settings == null ? 0 : 25,
            b -> { Network.action(0, mode.ordinal()); onClose(); });
        if (settings == null) return;
        String label = titleFor(settings);
        addRenderableWidget(Button.builder(Component.literal("⚙"), b -> show(settings))
            .bounds((width - panelWidth()) / 2 + panelWidth() - 22, top() + 28 + index * 22, 22, 20)
            .tooltip(Tooltip.create(Component.literal(label)))
            .createNarration(supplier -> Component.literal(label)).build());
    }
    private static String titleFor(Page page) {
        return switch (page) {
            case SPHERE -> "球体设置";
            case TRIANGLE -> "三角形设置";
            case CYLINDER -> "圆柱设置";
            default -> "高级几何体";
        };
    }
    private void geometryPage() {
        if (page == Page.GEOMETRY) {
            shape(0, Mode.CIRCLE, null); shape(1, Mode.CURVE, null);
            shape(2, sphere, Page.SPHERE);
            shape(3, triangle, Page.TRIANGLE); shape(4, cylinder, Page.CYLINDER);
            row(5, "返回主菜单", 0, b -> show(Page.MAIN));
            return;
        }
        Mode[] options = switch (page) {
            case SPHERE -> new Mode[]{Mode.SPHERE, Mode.SHELL};
            case TRIANGLE -> new Mode[]{Mode.TRIANGLE, Mode.EDGES};
            case CYLINDER -> new Mode[]{Mode.CYLINDER, Mode.WALL, Mode.CAPPED};
            default -> throw new IllegalStateException("Invalid settings page");
        };
        for (int i = 0; i < options.length; i++) {
            Mode option = options[i];
            row(i, ((option == sphere || option == triangle || option == cylinder) ? "● " : "") + option.label, 0, b -> {
                if (page == Page.SPHERE) sphere = option;
                else if (page == Page.TRIANGLE) triangle = option;
                else cylinder = option;
                show(Page.GEOMETRY);
            });
        }
        row(options.length, "返回高级几何体", 0, b -> show(Page.GEOMETRY));
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
        if (page != Page.MAIN) {
            geometryPage();
            return;
        }
        mode(0, 0, Mode.SOLID); mode(0, 1, Mode.HOLLOW); mode(0, 2, Mode.FRAME);
        mode(0, 3, Mode.LINE); mode(0, 4, Mode.MEASURE);
        button(0, 5, "高级几何体 ›", b -> show(Page.GEOMETRY));
        mode(1, 0, Mode.COPY); mode(1, 1, Mode.CUT); mode(1, 2, Mode.PASTE);
        mode(1, 3, Mode.REPLACE); mode(1, 4, Mode.CLEAR);
        mode(1, 5, Mode.STACK);
        mode(1, 6, Mode.FELL);
        mode(2, 0, Mode.NONE);
        overwrite = button(2, 4, "", b -> Network.action(9, ClientController.status.getBoolean("overwrite") ? 0 : 1));
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
        } else if (page != Page.MAIN) {
            graphics.drawCenteredString(font, titleFor(page), width / 2, top() + 8, 0xFFE6A3);
        } else {
            graphics.drawCenteredString(font, "交互建造", width / 2, top() + 4, 0xFFE6A3);
            String[] labels = {"建造形状", "编辑操作", "模式设置"};
            for (int i = 0; i < labels.length; i++)
                graphics.drawCenteredString(font, labels[i], left(i) + colWidth() / 2, top() + 21, 0xFFFFFF);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && page != Page.MAIN
            && !ClientController.status.getString("phase").equals("WARNING")) {
            show(page == Page.GEOMETRY ? Page.MAIN : Page.GEOMETRY);
            return true;
        }
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
