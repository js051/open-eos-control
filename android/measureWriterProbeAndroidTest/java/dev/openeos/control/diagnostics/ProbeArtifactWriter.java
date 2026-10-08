package dev.openeos.control.diagnostics;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Publish only after all fallible writing/closing/logging succeeds. Never clean up other files. */
public final class ProbeArtifactWriter {
    @FunctionalInterface public interface Opener { OutputStream open() throws Exception; }
    @FunctionalInterface public interface Promotion { void move(Path source, Path target) throws Exception; }
    private ProbeArtifactWriter() {}

    public static void commit(Path root, Path pending, Path target, String text,
                              Opener opener, Runnable log) throws Exception {
        commit(root, pending, target, text, opener, log,
                (source, destination) -> Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE));
    }
    // Separate promotion seam permits deterministic failure tests without changing filesystem permissions.
    public static void commit(Path root, Path pending, Path target, String text,
                              Opener opener, Runnable log, Promotion promotion) throws Exception {
        if (!pending.getParent().equals(root) || !target.getParent().equals(root)
                || !pending.getFileName().toString().endsWith(".pending")
                || !target.getFileName().toString().endsWith(".txt")) {
            throw new IOException("Probe output escaped collector directory");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Probe output name collision");
        Files.createDirectories(root);
        Files.createFile(pending); // Exclusive creation: do not truncate an older pending artifact.
        try (OutputStream output = opener.open()) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
        log.run();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Probe output name collision");
        // FINAL operation. No logging, flushing, closing or cleanup may follow successful publication.
        promotion.move(pending, target);
    }
}
