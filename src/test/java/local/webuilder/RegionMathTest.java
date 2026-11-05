package local.webuilder;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegionMathTest {
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
