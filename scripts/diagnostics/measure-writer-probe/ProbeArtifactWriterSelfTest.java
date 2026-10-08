import dev.openeos.control.diagnostics.ProbeArtifactWriter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Deterministic commit-failure cases; no permission changes, retries or Android execution. */
public final class ProbeArtifactWriterSelfTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("eos-probe-commit-");
        String text = "PROOF_VALID synthetic complete bytes\nREPORT_COMPLETE\n";
        for (String failure : new String[]{"interrupted-write", "flush", "close", "logging", "promotion"}) {
            Path pending = root.resolve(failure + ".pending"), target = root.resolve(failure + ".txt");
            boolean[] promotionReached = {false};
            try {
                ProbeArtifactWriter.commit(root, pending, target, text, () -> new FilterOutputStream(Files.newOutputStream(pending)) {
                    @Override public void write(byte[] bytes) throws IOException {
                        if (failure.equals("interrupted-write")) { out.write(bytes, 0, 3); throw new IOException("synthetic interruption"); }
                        out.write(bytes);
                    }
                    @Override public void flush() throws IOException {
                        out.flush(); if (failure.equals("flush")) throw new IOException("synthetic flush failure after bytes");
                    }
                    @Override public void close() throws IOException {
                        out.close(); if (failure.equals("close")) throw new IOException("synthetic close failure after complete bytes");
                    }
                }, () -> { if (failure.equals("logging")) throw new IllegalStateException("synthetic logging failure"); },
                (source, destination) -> {
                    promotionReached[0] = true;
                    if (failure.equals("promotion")) throw new IOException("synthetic promotion failure");
                    Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
                });
                throw new AssertionError("failure was hidden: " + failure);
            } catch (IOException | IllegalStateException expected) { /* Expected first failure, no retry. */ }
            check(promotionReached[0] == failure.equals("promotion"), "earlier failure was swallowed before promotion: " + failure);
            check(!Files.exists(target), "invalid final published: " + failure);
            check(Files.exists(pending), "pending evidence unexpectedly removed");
            if (!failure.equals("interrupted-write")) check(Files.readString(pending).equals(text), "complete-bytes case did not have complete bytes");
        }
        Path pending = root.resolve("success.pending"), target = root.resolve("success.txt");
        ProbeArtifactWriter.commit(root, pending, target, text, () -> Files.newOutputStream(pending), () -> {});
        check(Files.readString(target).equals(text) && !Files.exists(pending), "atomic success not committed");
        Path collisionPending = root.resolve("collision.pending");
        try {
            ProbeArtifactWriter.commit(root, collisionPending, target, "must not overwrite", () -> Files.newOutputStream(collisionPending), () -> {});
            throw new AssertionError("existing final accepted");
        } catch (IOException expected) { /* Refuse collision, retain other artifact. */ }
        check(Files.readString(target).equals(text) && !Files.exists(collisionPending), "unrelated valid artifact changed");
        Path oldPending = root.resolve("old.pending"); Files.writeString(oldPending, "old evidence");
        try {
            ProbeArtifactWriter.commit(root, oldPending, root.resolve("old.txt"), text, () -> Files.newOutputStream(oldPending), () -> {});
            throw new AssertionError("existing pending accepted");
        } catch (IOException expected) { /* Exclusive create must fail. */ }
        check(Files.readString(oldPending).equals("old evidence"), "older pending artifact overwritten");
        System.out.println("PASS interrupted-write, flush/close after complete bytes, logging and promotion failures leave no accepted final");
        System.out.println("PASS atomic commit success; prior final/pending files preserved without cleanup");
    }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
