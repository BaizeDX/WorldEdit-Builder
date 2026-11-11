package local.webuilder;

public enum Mode {
    NONE("无"), SOLID("实心立方体"), HOLLOW("空心立方体"), FRAME("框架"),
    CUT("剪切"), COPY("复制"), PASTE("粘贴"), REPLACE("替换"), CLEAR("两点清除"), LINE("两点连线"), CIRCLE("两点画圆"), CURVE("多点曲线"), MEASURE("两点测量"),
    SPHERE("实心球"), SHELL("空心球"), TRIANGLE("填充三角形"), EDGES("边框三角形"),
    CYLINDER("实心圆柱"), WALL("圆柱侧壁"), CAPPED("侧壁加顶底"), STACK("延伸复制");

    boolean sphere() { return this == SPHERE || this == SHELL; }
    boolean triangle() { return this == TRIANGLE || this == EDGES; }
    boolean cylinder() { return this == CYLINDER || this == WALL || this == CAPPED; }
    boolean advancedShape() { return sphere() || triangle() || cylinder(); }

    public final String label;
    Mode(String label) { this.label = label; }
}
