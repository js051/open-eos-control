import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import androidx.test.runner.AndroidJUnitRunner;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.platform.io.FileTestStorage;
import dev.openeos.control.diagnostics.MeasureWriterProbeRunner;
import dev.openeos.control.diagnostics.MeasureWriterRecorder;
import java.nio.file.*;

/** JVM-only lifecycle/provider spies. This is not Android collection validation. */
public final class ProbeRunnerSelfTest {
    public static void main(String[] args) throws Exception {
        Looper.getMainLooper();
        Path root = Files.createTempDirectory("eos-probe-runner-");
        FileTestStorage.root = root;
        InstrumentationRegistry.arguments.putString("additionalTestOutputDir", root.toString());
        InstrumentationRegistry.arguments.putString("eosMeasureWriterInvocation", "synthetic-fresh-invocation");
        Path app = root.resolve("synthetic-app.apk"), test = root.resolve("synthetic-test.apk");
        Files.writeString(app, "synthetic-app"); Files.writeString(test, "synthetic-test");
        AndroidJUnitRunner.targetContext.info.sourceDir = app.toString();
        AndroidJUnitRunner.instrumentationContext.info.sourceDir = test.toString();
        Thread.UncaughtExceptionHandler prior = Thread.getDefaultUncaughtExceptionHandler();
        MeasureWriterProbeRunner runner = new MeasureWriterProbeRunner(); runner.onStart();
        check(report(root, "start").contains("START_COMPLETE"), "start artifact absent");
        int beforeHooks = Log.lines.size();
        Object delegate = new Object(), node = new Object();
        MeasureWriterRecorder.applied(MeasureWriterRecorder.MANIFEST);
        MeasureWriterRecorder.entry(delegate, node, false, "synthetic()V");
        MeasureWriterRecorder.write(true, delegate, node, false, "synthetic()V#1", 0);
        MeasureWriterRecorder.write(true, delegate, node, true, "synthetic()V#1", 1);
        MeasureWriterRecorder.write(false, delegate, node, true, "synthetic()V#2", 0);
        MeasureWriterRecorder.write(false, delegate, node, false, "synthetic()V#2", 1);
        MeasureWriterRecorder.exit(delegate, node, false, "synthetic()V");
        check(Log.lines.size() == beforeHooks, "layout hook exported");
        RuntimeException original = new RuntimeException("synthetic original");
        check(runner.onException(delegate, original), "delegate return changed");
        check(AndroidJUnitRunner.seenObject == delegate && AndroidJUnitRunner.seenFailure == original, "callback input changed");
        String fatal = report(root, "fatal");
        check(fatal.contains("BOUNDARY fatal") && fatal.contains("REPORT_COMPLETE") && fatal.contains("APK app="), "crash artifact incomplete");
        Bundle result = new Bundle(); result.putString("stream", "original stream"); runner.finish(-1, result);
        check(AndroidJUnitRunner.finishCode == -1, "original result changed");
        check(AndroidJUnitRunner.finishResults.getString("stream", "").startsWith("original stream\nPROOF_VALID"), "stream proof lost");
        check(report(root, "finish").contains("BOUNDARY finish"), "normal artifact absent");
        check(Thread.getDefaultUncaughtExceptionHandler() == prior, "handler replaced");
        FileTestStorage.fail = true;
        MeasureWriterProbeRunner broken = new MeasureWriterProbeRunner(); broken.onStart();
        Bundle existingFailure = new Bundle(); existingFailure.putString("shortMsg", "original error"); broken.finish(-1, existingFailure);
        check(AndroidJUnitRunner.finishCode == 0, "storage failure silently green");
        check(AndroidJUnitRunner.finishResults.getString("shortMsg", "").startsWith("original error; "), "prior failure lost");
        AndroidJUnitRunner.throwFromDelegate = original;
        try { broken.onException(delegate, original); throw new AssertionError("delegate exception swallowed"); }
        catch (RuntimeException expected) { check(expected == original, "throwable replaced"); }
        check(Log.lines.stream().anyMatch(s -> s.contains("PROOF_INVALID storage/export failure")), "fallback absent");
        FileTestStorage.fail = false;
        AndroidJUnitRunner.throwFromDelegate = null;
        InstrumentationRegistry.arguments.putString("eosMeasureWriterInvocation", "synthetic-split");
        AndroidJUnitRunner.targetContext.info.splitSourceDirs = new String[]{"synthetic-split.apk"};
        new MeasureWriterProbeRunner().onStart();
        try (var files = Files.list(root)) {
            check(files.noneMatch(p -> p.getFileName().toString().startsWith("eos-measure-writer-synthetic-split-")), "split APK identity accepted");
        }
        AndroidJUnitRunner.targetContext.info.splitSourceDirs = null;
        InstrumentationRegistry.arguments.putString("eosMeasureWriterInvocation", "synthetic-missing-apk");
        AndroidJUnitRunner.targetContext.info.sourceDir = root.resolve("missing.apk").toString();
        new MeasureWriterProbeRunner().onStart();
        try (var files = Files.list(root)) {
            check(files.noneMatch(p -> p.getFileName().toString().startsWith("eos-measure-writer-synthetic-missing-apk-")), "missing APK accepted");
        }
        System.out.println("PASS missing/split APK layouts fail identity before output publication");
        System.out.println("PASS committed normal/crash exports, app/test hash and invocation headers, normal stream proof");
        System.out.println("PASS unchanged callback inputs/return/throwable/handler; storage failure retains original error");
    }
    static String report(Path root, String kind) throws Exception {
        try (var files = Files.list(root)) {
            return Files.readString(files.filter(p -> p.getFileName().toString().endsWith("-" + kind + ".txt")).findFirst().orElseThrow());
        }
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
