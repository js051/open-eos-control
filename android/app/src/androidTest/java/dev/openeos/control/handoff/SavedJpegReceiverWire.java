package dev.openeos.control.handoff;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;
import java.io.FileNotFoundException;

/** Framework-only support: the test APK's standalone process cannot load app dependencies. */
final class SavedJpegReceiverWire {
    static volatile java.util.concurrent.CountDownLatch receiptGate;
    static final String ACTION = "dev.photo.workflow.action.IMPORT_CAMERA_MEDIA";
    static final String MIME = "application/vnd.openeos.camera-import.v1+json";
    static final String RECEIPT_MIME = "application/vnd.openeos.camera-import-receipt.v1+json";
    static final String PROVIDER = "dev.openeos.control";

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("synthetic-saved-jpeg-receiver", Context.MODE_PRIVATE);
    }

    static String authority(Context context) {
        return context.getPackageName() + ".saved_jpeg_receipts";
    }

    static File directory(Context context) {
        return new File(context.getCacheDir(), "synthetic-saved-jpeg-receipts");
    }

    static File receiptFile(Context context, Uri uri) throws FileNotFoundException {
        if (!"content".equals(uri.getScheme()) || !authority(context).equals(uri.getAuthority())
                || uri.getPathSegments().size() != 2
                || !"receipt".equals(uri.getPathSegments().get(0))
                || !uri.getPathSegments().get(1).matches("[a-f0-9-]{36}\\.json")
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new FileNotFoundException("Not an owned synthetic receipt");
        }
        return new File(directory(context), uri.getLastPathSegment());
    }

    private SavedJpegReceiverWire() {}
}
