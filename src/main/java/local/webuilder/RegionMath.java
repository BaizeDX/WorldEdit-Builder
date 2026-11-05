package local.webuilder;

/** Inclusive cuboid arithmetic, shared by editing and regression checks. */
final class RegionMath {
    private RegionMath() { }
    static long volume(int width, int height, int depth) {
        if (width <= 0 || height <= 0 || depth <= 0) throw new IllegalArgumentException("无效选区尺寸");
        return Math.multiplyExact(Math.multiplyExact((long) width, height), depth);
    }
    static boolean includes(Mode mode, int x, int y, int z, int width, int height, int depth) {
        int boundaries = 0;
        if (x == 0 || x == width - 1) boundaries++;
        if (y == 0 || y == height - 1) boundaries++;
        if (z == 0 || z == depth - 1) boundaries++;
        return switch (mode) {
            case HOLLOW -> boundaries >= 1;
            case FRAME -> boundaries >= 2;
            default -> true;
        };
    }
}
