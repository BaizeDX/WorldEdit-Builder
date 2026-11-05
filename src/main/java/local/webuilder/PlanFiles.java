package local.webuilder;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;

/** Locks distinguish abandoned execution plans from files used by another running instance. */
final class PlanFiles implements AutoCloseable {
    final Path path;
    private final Path lockPath;
    private final FileChannel channel;
    private final FileLock lock;

    private PlanFiles(Path path, Path lockPath, FileChannel channel, FileLock lock) {
        this.path = path; this.lockPath = lockPath; this.channel = channel; this.lock = lock;
    }
    static PlanFiles create() throws IOException {
        return create(Path.of(System.getProperty("java.io.tmpdir"), "worldedit-builder-plans"));
    }
    static PlanFiles create(Path directory) throws IOException {
        Files.createDirectories(directory);
        cleanAbandoned(directory);
        Path lockPath = Files.createTempFile(directory, "plan-", ".lock");
        FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.lock();
            Path path = planPath(lockPath);
            Files.createFile(path);
            return new PlanFiles(path, lockPath, channel, lock);
        } catch (IOException | RuntimeException e) {
            channel.close(); Files.deleteIfExists(lockPath); throw e;
        }
    }
    private static Path planPath(Path lockPath) {
        String name = lockPath.getFileName().toString();
        return lockPath.resolveSibling(name.substring(0, name.length() - 5) + ".plan");
    }
    static void cleanAbandoned(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "plan-*.lock")) {
            for (Path entry : entries) {
                boolean abandoned = false;
                try (FileChannel channel = FileChannel.open(entry, StandardOpenOption.WRITE)) {
                    try (FileLock lock = channel.tryLock()) {
                        if (lock != null) {
                            Files.deleteIfExists(planPath(entry));
                            abandoned = true;
                        }
                    }
                } catch (OverlappingFileLockException ignored) {
                    // Another job in this JVM owns this plan.
                } catch (IOException ignored) {
                    // Another process may be opening, deleting, or holding the file.
                }
                if (abandoned) Files.deleteIfExists(entry);
            }
        }
    }
    @Override public void close() throws IOException {
        try { Files.deleteIfExists(path); }
        finally {
            try { lock.release(); }
            finally { channel.close(); }
        }
        Files.deleteIfExists(lockPath);
    }
}
