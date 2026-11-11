package local.webuilder;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/** Centripetal Catmull-Rom spans sampled in local coordinates for mirror and translation symmetry. */
final class CurvePath {
    private record Span(BlockPos start, BlockPos end, Vec3 a, Vec3 b, Vec3 tangent, int divisions) {
        BlockPos sample(int i) {
            if (i == divisions) return end;
            double t = (double) i / divisions;
            Vec3 offset = a.scale(t).add(b).scale(t).add(tangent).scale(t);
            return start.offset(round(offset.x), round(offset.y), round(offset.z));
        }
        private static int round(double value) {
            return (int) Math.copySign(Math.floor(Math.abs(value) + 0.5), value);
        }
    }
    private final Span[] spans;
    final LongArrayList cells = new LongArrayList();
    final LongOpenHashSet visited = new LongOpenHashSet();
    long estimate = 1;
    BlockPos min, max;
    private int segment, division;
    private BlockPos from, to;
    private long step, steps;
    boolean complete;

    CurvePath(List<BlockPos> controls) {
        spans = new Span[controls.size() - 1];
        min = max = to = controls.get(0);
        from = to;
        for (int i = 0; i < spans.length; i++) {
            BlockPos start = controls.get(i), end = controls.get(i + 1);
            Vec3 delta = vector(start, end);
            double knot = Math.sqrt(delta.length());
            Vec3 m = i == 0 ? delta : tangent(vector(controls.get(i - 1), start), delta, knot);
            Vec3 n = i + 1 == spans.length ? delta : tangent(delta, vector(end, controls.get(i + 2)), knot);
            Vec3 a = m.add(n).subtract(delta.scale(2));
            Vec3 b = delta.scale(3).subtract(m.scale(2)).subtract(n);
            // Linear interpolation error is bounded by max |second derivative| / (8 * divisions^2).
            double acceleration = Math.max(b.scale(2).length(), a.scale(6).add(b.scale(2)).length());
            int divisions = Math.max(1, (int) Math.ceil(Math.sqrt(acceleration / 2)));
            spans[i] = new Span(start, end, a, b, m, divisions);
            if (divisions == 1) estimate += distance(start, end);
            else {
                // Cubic Bezier control polygon bounds arc length; allow rounding at chord endpoints.
                estimate += (long) Math.ceil((m.length() + n.length()) / 3
                    + delta.subtract(m.add(n).scale(1.0 / 3)).length()) + divisions;
            }
        }
    }
    private static Vec3 vector(BlockPos a, BlockPos b) {
        return new Vec3(b.getX()-a.getX(), b.getY()-a.getY(), b.getZ()-a.getZ());
    }
    private static Vec3 tangent(Vec3 left, Vec3 right, double span) {
        double l = Math.sqrt(left.length()), r = Math.sqrt(right.length());
        return left.scale(r / l).add(right.scale(l / r)).scale(span / (l + r));
    }
    private static long distance(BlockPos a, BlockPos b) {
        return Math.max(Math.abs(a.getX()-b.getX()), Math.max(Math.abs(a.getY()-b.getY()), Math.abs(a.getZ()-b.getZ())));
    }
    // Do at most one chord transition or voxel per call, including collapsed chords.
    void advance(net.minecraft.server.level.ServerLevel level) {
        if (step > steps) {
            if (segment == spans.length) { complete = true; return; }
            Span span = spans[segment];
            from = to;
            to = span.sample(++division);
            steps = distance(from, to);
            step = 1;
            if (division == span.divisions) { segment++; division = 0; }
            return;
        }
        BlockPos p = new BlockPos(RegionMath.lineCoordinate(from.getX(), to.getX(), step, steps),
            RegionMath.lineCoordinate(from.getY(), to.getY(), step, steps),
            RegionMath.lineCoordinate(from.getZ(), to.getZ(), step, steps));
        step++;
        if (!level.isInWorldBounds(p)) throw new IllegalArgumentException("曲线超出世界范围。");
        if (visited.add(p.asLong())) {
            cells.add(p.asLong());
            min = new BlockPos(Math.min(min.getX(), p.getX()), Math.min(min.getY(), p.getY()), Math.min(min.getZ(), p.getZ()));
            max = new BlockPos(Math.max(max.getX(), p.getX()), Math.max(max.getY(), p.getY()), Math.max(max.getZ(), p.getZ()));
        }
    }
}
