package dev.openeos.control.handoff;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Non-exported, read-only test APK provider. Only an ActivityResult URI grant permits EOS reads. */
public final class SavedJpegReceiptProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) { return SavedJpegReceiverWire.RECEIPT_MIME; }

    @Override public synchronized ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("Synthetic receipts are read-only");
        File file = SavedJpegReceiverWire.receiptFile(getContext(), uri);
        ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        android.content.SharedPreferences preferences = SavedJpegReceiverWire.preferences(getContext());
        if (!preferences.edit()
                .putInt("receipt_reads", preferences.getInt("receipt_reads", 0) + 1)
                .putInt("receipt_provider_uid", Process.myUid())
                .putInt("receipt_reader_uid", Binder.getCallingUid()).commit()) {
            try { descriptor.close(); } catch (java.io.IOException ignored) { }
            throw new FileNotFoundException("Could not record synthetic receipt evidence");
        }
        java.util.concurrent.CountDownLatch gate = SavedJpegReceiverWire.receiptGate;
        if (gate != null) {
            try {
                if (!preferences.edit().putBoolean("receipt_blocked", true).commit()
                        || !gate.await(60, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new FileNotFoundException("Synthetic receipt gate timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                try { descriptor.close(); } catch (java.io.IOException ignored) { }
                throw new FileNotFoundException("Synthetic receipt gate interrupted");
            } catch (FileNotFoundException failure) {
                try { descriptor.close(); } catch (java.io.IOException ignored) { }
                throw failure;
            } finally {
                preferences.edit().putBoolean("receipt_blocked", false).commit();
            }
        }
        return descriptor;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        try {
            File file = SavedJpegReceiverWire.receiptFile(getContext(), uri);
            MatrixCursor result = new MatrixCursor(projection == null
                    ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection);
            MatrixCursor.RowBuilder row = result.newRow();
            for (String column : result.getColumnNames()) {
                row.add(OpenableColumns.DISPLAY_NAME.equals(column) ? "synthetic-receipt.json"
                        : OpenableColumns.SIZE.equals(column) ? file.length() : null);
            }
            return result;
        } catch (FileNotFoundException failure) {
            throw new IllegalArgumentException("Not an owned synthetic receipt");
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw readOnly(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw readOnly(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw readOnly(); }
    private static UnsupportedOperationException readOnly() {
        return new UnsupportedOperationException("Synthetic receipts are read-only");
    }
}
