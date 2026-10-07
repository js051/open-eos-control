package dev.openeos.control.handoff;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A deliberately synthetic receiver in the separately installed test APK, never Serein itself.
 * Uses framework/Java/JSONObject only. No target classes, shared UID, shell grants, persistent
 * grants, external storage permission, or exportable receipt provider can hide a grant failure.
 */
public final class SavedJpegReceiverActivity extends Activity {
    public static final String CONTROL_ACTION = "dev.openeos.control.test.SAVED_JPEG_RECEIVER_CONTROL";
    public static final String OPERATION = "operation";
    public static final String MODE = "mode";
    public static final String REPORT = "report";
    public static final String CONTROL_BINDER = "control-binder";
    public static final String CONTROL_DESCRIPTOR = "dev.openeos.control.test.SavedJpegReceiverControl";
    public static final int CONTROL_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION;
    public static final String CONFIGURE = "configure";
    public static final String INSPECT = "inspect";
    public static final String CLEAN = "clean";
    public static final String HOLD_GRANTS = "hold-grants";
    public static final String PROBE_HELD_GRANTS = "probe-held-grants";
    public static final String FINISH_HELD = "finish-held";
    public static final String READY_CALLBACK = "ready-callback";
    public static final String RELEASE_RECEIPT = "release-receipt";
    public static final String HOLD_RESULT = "hold-result";
    public static final String BLOCK_RECEIPT = "block-receipt";
    public static final String VALID = "valid";
    public static final String CANCEL = "cancel";
    public static final String MISSING_RECEIPT = "missing-receipt";
    public static final String WRONG_SESSION = "wrong-session";
    public static final String WRONG_HASH = "wrong-hash";
    public static final String INCOMPLETE_COVERAGE = "incomplete-coverage";
    public static final String MISSING_RECEIPT_GRANT = "missing-receipt-grant";
    public static final String EXPECT_NO_INPUT_GRANT = "expect-no-input-grant";
    private static final int ALL_GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
    private static SavedJpegReceiverActivity heldActivity;
    private boolean holdingGrants;
    private Intent heldResult;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        JSONObject report = new JSONObject();
        try {
            String caller = getCallingPackage();
            // A control launch is restricted to this test APK's own instrumentation target.
            String target = getPackageName().replaceFirst("\\.test$", "");
            require(target.equals(caller), "unexpected-caller");
            int callerUid = getPackageManager().getApplicationInfo(caller, 0).uid;
            require(callerUid != Process.myUid(), "receiver-must-have-separate-uid");
            report.put("receiver_uid", Process.myUid()).put("caller_uid", callerUid);
            if (CONTROL_ACTION.equals(getIntent().getAction())) {
                control(report, callerUid);
            } else {
                receive(caller, report);
            }
        } catch (Exception failure) {
            // A fixed fixture diagnostic never serializes provider exceptions or raw source URIs.
            try {
                report.put("failure", "receiver-validation-failed");
                SavedJpegReceiverWire.preferences(this).edit().putString(REPORT, report.toString()).commit();
            } catch (Exception ignored) { }
            setResult(RESULT_CANCELED, new Intent().putExtra(REPORT, report.toString()));
        } finally {
            if (!holdingGrants) finish();
        }
    }

    private void control(JSONObject identity, int callerUid) throws Exception {
        String operation = getIntent().getStringExtra(OPERATION);
        if (HOLD_GRANTS.equals(operation)) {
            require(heldActivity == null, "another-held-fixture-is-active");
            ClipData clip = getIntent().getClipData();
            require(clip != null && clip.getItemCount() > 1, "missing-held-grants");
            require((getIntent().getFlags() & ALL_GRANTS) == Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    "held-grants-must-be-temporary-read-only");
            for (int i = 0; i < clip.getItemCount(); i++) {
                readOriginal(clip.getItemAt(i).getUri(), getCallingPackage(), 16 * 1024 * 1024);
            }
            identity.put("held_count", clip.getItemCount());
            saveReport(identity);
            android.os.ResultReceiver ready = getIntent().getParcelableExtra(READY_CALLBACK);
            require(ready != null, "missing-held-ready-callback");
            heldActivity = this;
            holdingGrants = true;
            ready.send(RESULT_OK, null);
            // Never leave a fixture Activity open indefinitely after an assertion failure.
            new android.os.Handler(getMainLooper()).postDelayed(() -> {
                if (heldActivity == this) {
                    heldActivity = null;
                    holdingGrants = false;
                    setResult(RESULT_CANCELED);
                    finish();
                }
            }, 60_000L);
            return;
        }
        require(CONFIGURE.equals(operation), "control-capability-required");
        JSONObject report = fixtureControl(getApplicationContext(), operation,
                getIntent().getStringExtra(MODE), identity);
        Bundle result = new Bundle();
        result.putString(REPORT, report.toString());
        // This capability reaches only the validated, separate-UID ActivityResult caller.
        // Diagnostics/release must not launch another Activity while the real receiver is held.
        result.putBinder(CONTROL_BINDER, new ControlCapability(getApplicationContext(), callerUid));
        setResult(RESULT_OK, new Intent().putExtras(result));
    }

    private static JSONObject fixtureControl(Context context, String operation, String mode,
                                             JSONObject identity) throws Exception {
        SharedPreferences preferences = SavedJpegReceiverWire.preferences(context);
        if (RELEASE_RECEIPT.equals(operation) || CLEAN.equals(operation)) {
            java.util.concurrent.CountDownLatch gate = SavedJpegReceiverWire.receiptGate;
            if (gate != null) gate.countDown();
            SavedJpegReceiverWire.receiptGate = null;
            if (RELEASE_RECEIPT.equals(operation)) return identity;
        }
        if (PROBE_HELD_GRANTS.equals(operation)) {
            require(heldActivity != null, "held-fixture-is-not-active");
            ClipData clip = heldActivity.getIntent().getClipData();
            JSONArray readable = new JSONArray();
            JSONArray granted = new JSONArray();
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                granted.put(context.checkUriPermission(uri, Process.myPid(), Process.myUid(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED);
                try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                    require(input != null && input.read() >= 0, "remaining-original-unreadable");
                    readable.put(true);
                } catch (SecurityException expected) {
                    readable.put(false);
                }
            }
            identity.put("held_readable", readable).put("held_granted", granted);
            return identity;
        }
        if (FINISH_HELD.equals(operation) || CLEAN.equals(operation)) {
            if (heldActivity != null) {
                heldActivity.holdingGrants = false;
                if (heldActivity.heldResult != null) heldActivity.returnReceipt(heldActivity.heldResult);
                else heldActivity.setResult(RESULT_OK);
                heldActivity.finish();
                heldActivity = null;
            }
            if (FINISH_HELD.equals(operation)) return identity;
        }
        if (CONFIGURE.equals(operation) || CLEAN.equals(operation)) {
            File[] receipts = SavedJpegReceiverWire.directory(context).listFiles();
            if (receipts != null) for (File file : receipts) {
                require(file.isFile() && file.getName().matches("[a-f0-9-]{36}\\.json")
                        && file.delete(), "receipt-cleanup-failed");
            }
            require(preferences.edit().clear().putString(MODE, mode == null ? VALID : mode).commit(),
                    "fixture-configuration-failed");
            SavedJpegReceiverWire.receiptGate = BLOCK_RECEIPT.equals(mode)
                    ? new java.util.concurrent.CountDownLatch(1) : null;
        } else {
            require(INSPECT.equals(operation), "unknown-control-operation");
        }
        JSONObject report = new JSONObject(preferences.getString(REPORT, identity.toString()));
        report.put("receipt_reads", preferences.getInt("receipt_reads", 0))
                .put("receipt_provider_uid", preferences.getInt("receipt_provider_uid", -1))
                .put("receipt_reader_uid", preferences.getInt("receipt_reader_uid", -1))
                .put("receiver_launches", preferences.getInt("receiver_launches", 0))
                .put("receiver_results", preferences.getInt("receiver_results", 0))
                .put("receipt_blocked", preferences.getBoolean("receipt_blocked", false));
        return report;
    }

    /** Only the held Activity can deliver the actual receipt and its Android URI grant. */
    private static final class ControlCapability extends Binder {
        private final Context context;
        private final int callerUid;
        private final Handler main;

        ControlCapability(Context context, int callerUid) {
            this.context = context;
            this.callerUid = callerUid;
            this.main = new Handler(context.getMainLooper());
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code != CONTROL_TRANSACTION) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(CONTROL_DESCRIPTOR);
            if (Binder.getCallingUid() != callerUid) throw new SecurityException("unexpected-control-uid");
            String operation = data.readString();
            require(INSPECT.equals(operation) || PROBE_HELD_GRANTS.equals(operation)
                    || FINISH_HELD.equals(operation) || RELEASE_RECEIPT.equals(operation)
                    || CLEAN.equals(operation), "unknown-control-operation");
            ResultReceiver callback = ResultReceiver.CREATOR.createFromParcel(data);
            require(callback != null && data.dataAvail() == 0, "invalid-control-callback");
            // Serialize fixture Activity state on its own main thread. The Binder call itself
            // never waits for the main thread, the held Activity, or blocked receipt I/O.
            require(main.post(() -> {
                Bundle response = new Bundle();
                int resultCode = RESULT_OK;
                try {
                    JSONObject identity = new JSONObject().put("receiver_uid", Process.myUid())
                            .put("caller_uid", callerUid);
                    response.putString(REPORT, fixtureControl(context, operation, null, identity).toString());
                } catch (Exception failure) {
                    resultCode = RESULT_CANCELED;
                    response.putString(REPORT, "{\"failure\":\"receiver-control-failed\"}");
                }
                callback.send(resultCode, response);
            }), "control-main-thread-unavailable");
            reply.writeNoException();
            return true;
        }
    }

    private void receive(String caller, JSONObject report) throws Exception {
        SharedPreferences preferences = SavedJpegReceiverWire.preferences(this);
        require(preferences.edit().putInt("receiver_launches", preferences.getInt("receiver_launches", 0) + 1).commit(),
                "receiver-count-unavailable");
        Intent request = getIntent();
        require(SavedJpegReceiverWire.ACTION.equals(request.getAction()), "wrong-action");
        require(SavedJpegReceiverWire.MIME.equals(request.getType()), "wrong-mime");
        require(getPackageName().equals(request.getPackage()), "wrong-package");
        ClipData clip = request.getClipData();
        require(clip != null && clip.getItemCount() >= 2, "missing-manifest-or-original");
        Uri manifestUri = request.getData();
        require(manifestUri != null && manifestUri.equals(clip.getItemAt(0).getUri()), "manifest-not-first");
        report.put("manifest_first", true).put("clip_count", clip.getItemCount())
                .put("grant_flags", request.getFlags() & ALL_GRANTS);
        String mode = SavedJpegReceiverWire.preferences(this).getString(MODE, VALID);
        if (EXPECT_NO_INPUT_GRANT.equals(mode)) {
            require((request.getFlags() & ALL_GRANTS) == 0, "negative-control-has-grants");
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                require(checkUriPermission(uri, Process.myPid(), Process.myUid(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_DENIED,
                        "unexpected-read-permission");
                try (InputStream ignored = getContentResolver().openInputStream(uri)) {
                    throw new IllegalStateException("read-without-grant-succeeded");
                } catch (SecurityException expected) { }
            }
            report.put("input_reads_denied", clip.getItemCount());
            saveReport(report);
            setResult(RESULT_OK, new Intent().putExtra(REPORT, report.toString()));
            return;
        }
        require((request.getFlags() & ALL_GRANTS) == Intent.FLAG_GRANT_READ_URI_PERMISSION,
                "grant-must-be-temporary-read-only");
        JSONObject manifest = new JSONObject(new String(readOriginal(manifestUri, caller, 16 * 1024 * 1024),
                StandardCharsets.UTF_8));
        require("1.0".equals(manifest.getString("handoff_version"))
                && "1.0".equals(manifest.getString("contract_version"))
                && SavedJpegReceiverWire.PROVIDER.equals(manifest.getString("provider_id")), "wrong-wire-version");
        String session = manifest.getString("session_id");
        JSONArray items = manifest.getJSONArray("items");
        require(items.length() > 0 && items.length() + 1 == clip.getItemCount(), "wrong-clip-coverage");
        Set<String> mediaIds = new HashSet<>();
        JSONArray receipts = new JSONArray();
        JSONArray hashes = new JSONArray();
        JSONArray lengths = new JSONArray();
        int deniedWrites = 1;
        String receiptSession = WRONG_SESSION.equals(mode) ? "session-synthetic-wrong-session" : session;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            JSONObject descriptor = item.getJSONObject("descriptor");
            require(session.equals(descriptor.getString("session_id")), "mixed-session");
            require("JPEG".equals(descriptor.getString("media_kind"))
                    && "image/jpeg".equals(descriptor.getString("mime_type")), "not-original-jpeg");
            JSONArray representations = item.getJSONArray("representations");
            require(representations.length() == 1, "unexpected-representations");
            JSONObject original = representations.getJSONObject(0);
            require("ORIGINAL".equals(original.getString("representation")), "not-original");
            Uri uri = Uri.parse(original.getString("content_uri"));
            require(uri.equals(clip.getItemAt(i + 1).getUri()), "original-not-in-clip-order");
            byte[] bytes = readOriginal(uri, caller, 4 * 1024 * 1024);
            deniedWrites++;
            require(bytes.length > 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216,
                    "fixture-must-use-real-jpeg");
            String digest = hex(MessageDigest.getInstance("SHA-256").digest(bytes));
            JSONObject checksum = descriptor.getJSONObject("source_checksum");
            require("SHA-256".equals(checksum.getString("algorithm"))
                    && "FULL_ORIGINAL".equals(checksum.getString("scope"))
                    && digest.equals(checksum.getString("value"))
                    && bytes.length == descriptor.getLong("byte_length"), "original-evidence-mismatch");
            String mediaId = descriptor.getString("media_id");
            require(mediaIds.add(mediaId), "duplicate-media-id");
            hashes.put(digest);
            lengths.put(bytes.length);
            if (!(INCOMPLETE_COVERAGE.equals(mode) && i == items.length() - 1)) {
                char[] wrongDigest = new char[64];
                java.util.Arrays.fill(wrongDigest, digest.charAt(0) == 'a' ? 'b' : 'a');
                receipts.put(receipt(descriptor, receiptSession, i,
                        WRONG_HASH.equals(mode) ? new String(wrongDigest) : digest));
            }
        }
        report.put("session_id", session).put("original_count", items.length())
                .put("write_denials", deniedWrites).put("original_hashes", hashes)
                .put("original_lengths", lengths).put("original_evidence_matched", true);
        saveReport(report);
        if (CANCEL.equals(mode)) { setResult(RESULT_CANCELED); return; }
        if (MISSING_RECEIPT.equals(mode)) { setResult(RESULT_OK, new Intent()); return; }
        JSONObject batch = new JSONObject().put("handoff_version", "1.0").put("contract_version", "1.0")
                .put("provider_id", SavedJpegReceiverWire.PROVIDER).put("session_id", receiptSession)
                .put("receipts", receipts);
        File directory = SavedJpegReceiverWire.directory(this);
        require(directory.isDirectory() || directory.mkdirs(), "receipt-directory-unavailable");
        Uri receiptUri = new Uri.Builder().scheme("content").authority(SavedJpegReceiverWire.authority(this))
                .appendPath("receipt").appendPath(UUID.randomUUID() + ".json").build();
        try (FileOutputStream output = new FileOutputStream(SavedJpegReceiverWire.receiptFile(this, receiptUri))) {
            output.write(batch.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        Intent result = new Intent().setDataAndType(receiptUri, SavedJpegReceiverWire.RECEIPT_MIME);
        if (!MISSING_RECEIPT_GRANT.equals(mode)) {
            result.setClipData(ClipData.newRawUri("Synthetic receipt", receiptUri));
            result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        if (HOLD_RESULT.equals(mode)) {
            require(heldActivity == null, "another-held-fixture-is-active");
            report.put("result_held", true);
            saveReport(report);
            heldResult = result;
            heldActivity = this;
            holdingGrants = true;
            new android.os.Handler(getMainLooper()).postDelayed(() -> {
                if (heldActivity == this) {
                    heldActivity = null;
                    holdingGrants = false;
                    setResult(RESULT_CANCELED);
                    finish();
                }
            }, 60_000L);
            return;
        }
        returnReceipt(result);
    }

    private void returnReceipt(Intent result) {
        SharedPreferences preferences = SavedJpegReceiverWire.preferences(this);
        require(preferences.edit().putInt("receiver_results", preferences.getInt("receiver_results", 0) + 1).commit(),
                "receiver-result-count-unavailable");
        setResult(RESULT_OK, result);
    }

    private byte[] readOriginal(Uri uri, String caller, int maximumFixtureBytes) throws Exception {
        require("content".equals(uri.getScheme()) && (caller + ".camera_import").equals(uri.getAuthority()),
                "not-production-file-provider");
        require(checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                == PackageManager.PERMISSION_GRANTED, "missing-read-grant");
        require(checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                == PackageManager.PERMISSION_DENIED, "unexpected-write-grant");
        // Open without writing. A missing SecurityException makes the fixture fail immediately.
        try (ParcelFileDescriptor ignored = getContentResolver().openFileDescriptor(uri, "rw")) {
            throw new IllegalStateException("write-access-was-not-denied");
        } catch (SecurityException expected) { }
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            require(input != null, "unreadable-original");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                require(count > 0 && bytes.size() <= maximumFixtureBytes - count, "fixture-input-too-large");
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        }
    }

    private static JSONObject receipt(JSONObject descriptor, String session, int index, String digest)
            throws Exception {
        return new JSONObject().put("contract_version", "1.0").put("import_id", "import-synthetic-" + index)
                .put("provider_id", SavedJpegReceiverWire.PROVIDER).put("session_id", session)
                .put("media_id", descriptor.getString("media_id"))
                .put("outcome", index % 2 == 0 ? "IMPORTED" : "DUPLICATE")
                .put("asset_id", "asset-synthetic-" + index).put("blob_sha256", digest)
                .put("byte_length", descriptor.getLong("byte_length"))
                .put("capture_group_id", "group-synthetic-" + index)
                .put("preserved_representation_ids", new JSONArray().put("blob-synthetic-original-" + index))
                .put("safe_error_code", JSONObject.NULL).put("completed_at", "2026-10-07T00:00:00Z");
    }

    private void saveReport(JSONObject report) {
        require(SavedJpegReceiverWire.preferences(this).edit().putString(REPORT, report.toString()).commit(),
                "report-write-failed");
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private static void require(boolean condition, String safeReason) {
        if (!condition) throw new IllegalStateException(safeReason);
    }
}
