package local.webuilder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class PlanFilesTest {
    @TempDir Path directory;
    @Test void activePlansSurviveCleanupAndCloseRemovesFiles() throws Exception {
        Path path;
        try (PlanFiles active = PlanFiles.create(directory)) {
            path = active.path;
            Files.writeString(path, "active plan");
            PlanFiles.cleanAbandoned(directory);
            assertEquals("active plan", Files.readString(path));
        }
        assertFalse(Files.exists(path));
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }
    @Test void abandonedPlanIsRemovedWithoutTouchingUnrelatedFiles() throws Exception {
        Files.writeString(directory.resolve("plan-crashed.lock"), "");
        Files.writeString(directory.resolve("plan-crashed.plan"), "stale");
        Files.writeString(directory.resolve("other.txt"), "keep");
        PlanFiles.cleanAbandoned(directory);
        assertFalse(Files.exists(directory.resolve("plan-crashed.plan")));
        assertFalse(Files.exists(directory.resolve("plan-crashed.lock")));
        assertEquals("keep", Files.readString(directory.resolve("other.txt")));
    }
}
