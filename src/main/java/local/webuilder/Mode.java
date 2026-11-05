package local.webuilder;

public enum Mode {
    NONE("无"), SOLID("实心立方体"), HOLLOW("空心立方体"), FRAME("框架"),
    CUT("剪切"), COPY("复制"), PASTE("粘贴"), REPLACE("替换"), CLEAR("两点清除");

    public final String label;
    Mode(String label) { this.label = label; }
}
