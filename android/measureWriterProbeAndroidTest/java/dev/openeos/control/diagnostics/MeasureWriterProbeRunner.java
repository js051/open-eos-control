package dev.openeos.control.diagnostics;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import androidx.test.runner.AndroidJUnitRunner;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.platform.io.PlatformTestStorageRegistry;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

/** Existing lifecycle/crash callbacks only; no handler replacement or in-layout export. */
public final class MeasureWriterProbeRunner extends AndroidJUnitRunner {
    private final AtomicBoolean fatalExported = new AtomicBoolean();
    private final long session = System.nanoTime();
    private final int pid = Process.myPid();
    private volatile boolean storageFailed;
    private String invocation;
    private String appHash, testHash;
    private Path collector;

    @Override public void onStart() {
        try {
            Bundle arguments = InstrumentationRegistry.getArguments();
            invocation = arguments.getString("eosMeasureWriterInvocation", "");
            String outputDir = arguments.getString("additionalTestOutputDir", "");
            if (!invocation.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || outputDir.isEmpty()
                    || !PlatformTestStorageRegistry.getInstance().getClass().getName()
                    .equals("androidx.test.platform.io.FileTestStorage")) {
                throw new IllegalStateException("Current invocation/collector route missing or unsupported");
            }
            collector = new File(outputDir).getCanonicalFile().toPath();
            // Own project APKs only, once at startup. No package scan or arbitrary local file access.
            ApplicationInfo app = getTargetContext().getApplicationInfo();
            ApplicationInfo test = getContext().getApplicationInfo();
            if ((app.splitSourceDirs != null && app.splitSourceDirs.length != 0)
                    || (test.splitSourceDirs != null && test.splitSourceDirs.length != 0)) {
                throw new IllegalStateException("Split APKs are outside this probe's build identity contract");
            }
            Path appApk = new File(app.sourceDir).getCanonicalFile().toPath();
            Path testApk = new File(test.sourceDir).getCanonicalFile().toPath();
            if (appApk.equals(testApk)) throw new IllegalStateException("App and instrumentation APKs must be distinct");
            appHash = sha256(appApk);
            testHash = sha256(testApk);
            save("start", "EXPECTED " + MeasureWriterRecorder.MANIFEST
                    + "\nOUTPUT_ROUTE collectorArgument=true storage=androidx.test.platform.io.FileTestStorage\nSTART_COMPLETE\n");
        } catch (Throwable ignored) { storageFailure(); }
        super.onStart();
    }
    @Override public boolean onException(Object object, Throwable failure) {
        // The existing handler reaches this AFTER the real throw, then forwards that same Throwable.
        if (fatalExported.compareAndSet(false, true)) export("fatal");
        return super.onException(object, failure);
    }
    @Override public void finish(int resultCode, Bundle results) {
        export("finish");
        boolean valid = !storageFailed && MeasureWriterRecorder.hasRuntimeProof();
        String evidence = (valid ? "PROOF_VALID " : "PROOF_INVALID ") + MeasureWriterRecorder.proof();
        Bundle output = results == null ? new Bundle() : results;
        output.putString("eosMeasureWriterProof", evidence);
        output.putString("stream", output.getString("stream", "") + "\n" + evidence + "\n");
        if (!valid) {
            String priorFailure = output.getString("shortMsg", "");
            output.putString("shortMsg", priorFailure + (priorFailure.isEmpty() ? "" : "; ")
                    + "EOS writer probe runtime/storage proof missing or invalid");
            resultCode = Activity.RESULT_CANCELED;
        }
        super.finish(resultCode, output);
    }
    private void export(String boundary) {
        try { save(boundary, MeasureWriterRecorder.report(boundary)); }
        catch (Throwable ignored) { storageFailure(); }
    }
    private void save(String boundary, String report) throws Exception {
        if (collector == null || appHash == null || testHash == null) {
            throw new IllegalStateException("Probe output identity was not initialized");
        }
        String stem = "eos-measure-writer-" + invocation + "-" + session + "-" + pid + "-" + boundary;
        String pendingName = stem + ".pending", finalName = stem + ".txt";
        Path pending = fileOutput(pendingName), target = fileOutput(finalName);
        String text = "INVOCATION " + invocation + "\nSESSION token=" + session + " pid=" + pid
                + "\nAPK app=" + appHash + " test=" + testHash + "\n" + report;
        ProbeArtifactWriter.commit(collector, pending, target, text,
                () -> PlatformTestStorageRegistry.getInstance().openOutputFile(pendingName), () -> {
                    for (int start = 0; start < text.length(); start += 1500) {
                        Log.i(MeasureWriterRecorder.TAG, MeasureWriterRecorder.PREFIX + "boundary=" + boundary
                                + " chunk=" + (start / 1500) + "\n" + text.substring(start, Math.min(text.length(), start + 1500)));
                    }
                }); // Nothing fallible follows the final atomic promotion in this save.
    }
    private Path fileOutput(String name) throws Exception {
        Uri uri = PlatformTestStorageRegistry.getInstance().getOutputFileUri(name);
        if (!"file".equals(uri.getScheme())) throw new IllegalStateException("Unsupported probe output provider");
        Path path = new File(uri.getPath()).getCanonicalFile().toPath();
        if (!collector.equals(path.getParent())) throw new IllegalStateException("Probe output escaped collector directory");
        return path;
    }
    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) hex.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        return hex.toString();
    }
    private void storageFailure() {
        storageFailed = true;
        MeasureWriterRecorder.invalidate();
        try { Log.e(MeasureWriterRecorder.TAG, MeasureWriterRecorder.PREFIX + "PROOF_INVALID storage/export failure"); }
        catch (Throwable ignored) { /* Preserve the original failure/handler. */ }
    }
}
