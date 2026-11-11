package local.webuilder;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

record MeasureGuide(String dimension, BlockPos a, BlockPos b, Map<BlockPos, Boolean> markers) {
    MeasureGuide(String dimension, BlockPos a, BlockPos b) {
        this(dimension, a, b, marks(a,b));
    }
    private static Map<BlockPos, Boolean> marks(BlockPos a, BlockPos b) {
        var result = new LinkedHashMap<BlockPos, Boolean>();
        for (int twelfth : new int[]{4,8,3,6,9}) {
            result.put(new BlockPos(RegionMath.lineCoordinate(a.getX(), b.getX(), twelfth, 12),
                RegionMath.lineCoordinate(a.getY(), b.getY(), twelfth, 12),
                RegionMath.lineCoordinate(a.getZ(), b.getZ(), twelfth, 12)), twelfth % 3 == 0);
        }
        return Map.copyOf(result);
    }
    // Closest point of the finite guide to the forward view ray.
    double hitDistance(Vec3 eye, Vec3 view) {
        Vec3 start = Vec3.atCenterOf(a).subtract(eye);
        Vec3 span = Vec3.atCenterOf(b).subtract(Vec3.atCenterOf(a));
        double along = span.dot(view), depth = start.dot(view);
        Vec3 side = span.subtract(view.scale(along));
        Vec3 offset = start.subtract(view.scale(depth));
        double low = 0, high = 1;
        if (along > 0) low = Math.max(0, -depth / along);
        if (along < 0) high = Math.min(1, -depth / along);
        if (low > high || (along == 0 && depth < 0)) return Double.POSITIVE_INFINITY;
        double t = side.lengthSqr() < 1e-12 ? (along < 0 ? high : low)
            : Math.max(low, Math.min(high, -offset.dot(side)/side.lengthSqr()));
        return offset.add(side.scale(t)).lengthSqr() <= 0.25 ? Math.max(0, depth + along*t) : Double.POSITIVE_INFINITY;
    }
}
