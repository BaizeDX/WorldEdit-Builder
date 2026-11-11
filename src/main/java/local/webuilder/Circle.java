package local.webuilder;

import net.minecraft.core.BlockPos;

/** Enumerate four quarter arcs without allocating a disk or duplicating seams. */
final class Circle {
    private final long squared;
    final int radius;
    final int quarter;

    Circle(int dx, int dz) {
        squared = (long) dx * dx + (long) dz * dz;
        if (squared == 0) throw new IllegalArgumentException("圆的半径须大于零。");
        radius = outer(0);
        int diagonal = (int) Math.sqrt(squared / 2.0);
        if (outer(diagonal + 1) >= diagonal + 1) diagonal++;
        quarter = 2 * diagonal + (outer(diagonal) == diagonal ? 0 : 1);
    }

    private int outer(int minor) {
        return (int) Math.round(Math.sqrt(squared - (long) minor * minor));
    }

    boolean contains(int dx, int dz) {
        int minor = Math.min(Math.abs(dx), Math.abs(dz));
        return minor <= radius && Math.max(Math.abs(dx), Math.abs(dz)) == outer(minor);
    }

    BlockPos position(BlockPos center, long index) {
        int step = (int) (index % quarter);
        int minor = Math.min(step, quarter - step);
        int major = outer(minor);
        int x = step <= quarter / 2 ? major : minor;
        int z = step <= quarter / 2 ? minor : major;
        return switch ((int) (index / quarter)) {
            case 0 -> center.offset(x, 0, z);
            case 1 -> center.offset(-z, 0, x);
            case 2 -> center.offset(-x, 0, -z);
            default -> center.offset(z, 0, -x);
        };
    }
}
