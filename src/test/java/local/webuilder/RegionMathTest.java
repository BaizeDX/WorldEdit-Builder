package local.webuilder;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegionMathTest {
    @Test void lineStepsAreConnectedNearestAndMirrorInvariant() {
        for (int steps = 0; steps <= 32; steps++) {
            for (int delta = 0; delta <= steps; delta++) {
                int prev = 0;
                for (int i = 0; i <= steps; i++) {
                    int offset = RegionMath.lineCoordinate(0, delta, i, steps);
                    assertEquals(91 - offset, RegionMath.lineCoordinate(91, 91 - delta, i, steps));
                    assertEquals(-19 + offset, RegionMath.lineCoordinate(-19, -19 + delta, i, steps));
                    assertTrue(offset == prev || offset == prev + 1);
                    assertTrue(2 * Math.abs(offset * steps - delta * i) <= steps);
                    if (i == steps) assertEquals(delta, offset);
                    prev = offset;
                }
            }
        }
    }

    private int count(Mode mode, int w, int h, int d) {
        int result = 0;
        for (int y = 0; y < h; y++) for (int z = 0; z < d; z++) for (int x = 0; x < w; x++) {
            if (RegionMath.includes(mode, x, y, z, w, h, d)) result++;
        }
        return result;
    }
    @Test void distinctModesAndPreservedInterior() {
        assertEquals(125, count(Mode.SOLID, 5, 5, 5));
        assertEquals(98, count(Mode.HOLLOW, 5, 5, 5));
        assertEquals(44, count(Mode.FRAME, 5, 5, 5));
        assertFalse(RegionMath.includes(Mode.HOLLOW, 2, 2, 2, 5, 5, 5));
        assertFalse(RegionMath.includes(Mode.FRAME, 0, 2, 2, 5, 5, 5));
    }
    @Test void degenerateSelectionsDoNotDuplicateEdges() {
        assertEquals(1, count(Mode.FRAME, 1, 1, 1));
        assertEquals(16, count(Mode.FRAME, 5, 1, 5));
        assertEquals(25, count(Mode.HOLLOW, 5, 1, 5));
        assertEquals(7, count(Mode.FRAME, 1, 7, 1));
    }
    @Test void volumeUsesLongAndChecksOverflow() {
        assertEquals(3_000_000_000L, RegionMath.volume(100000, 300, 100));
        assertThrows(IllegalArgumentException.class, () -> RegionMath.volume(0, 3, 3));
        assertThrows(ArithmeticException.class, () -> RegionMath.volume(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
    }
}
