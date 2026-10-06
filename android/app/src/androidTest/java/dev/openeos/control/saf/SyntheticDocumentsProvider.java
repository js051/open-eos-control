package dev.openeos.control.saf;

import android.content.pm.ProviderInfo;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Real SAF fixture hosted by the separately installed test APK. It deliberately uses only
 * framework/Java classes: the standalone provider process cannot load target-app dependencies.
 * No shell grants, shared UID, unprotected control endpoint, or persistent grants are needed.
 */
public final class SyntheticDocumentsProvider extends DocumentsProvider {
    public static final String NORMAL_ROOT = "normal";
    public static final String REFUSING_ROOT = "refusing";
    public static final String NORMAL_TITLE = "Open EOS synthetic files";
    public static final String REFUSING_TITLE = "Open EOS cleanup refusal";
    public static final String SENTINEL = "UNRELATED-SYNTHETIC-SENTINEL.bin";
    public static final String CREATED_IDS = "synthetic_created_ids";
    public static final String DELETED_IDS = "synthetic_deleted_ids";
    public static final String REFUSED_IDS = "synthetic_refused_ids";
    public static final String PROVIDER_UID = "synthetic_provider_uid";
    public static final String CALLER_UID = "synthetic_caller_uid";

    private static final String[] ROOT_COLUMNS = {
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES, Root.COLUMN_ICON
    };
    private static final String[] DOCUMENT_COLUMNS = {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
    };
    private final List<String> createdIds = new ArrayList<>();
    private final List<String> deletedIds = new ArrayList<>();
    private final List<String> refusedIds = new ArrayList<>();
    private File storage;
    private String authority;

    public static byte[] sentinelBytes() {
        return "SYNTHETIC unrelated document: keep these bytes".getBytes(StandardCharsets.UTF_8);
    }

    @Override public void attachInfo(Context context, ProviderInfo info) {
        authority = info.authority;
        super.attachInfo(context, info);
    }

    @Override public boolean onCreate() {
        storage = new File(getContext().getCacheDir(), "synthetic-saf-documents");
        for (String rootId : Arrays.asList(NORMAL_ROOT, REFUSING_ROOT)) {
            File root = new File(storage, rootId);
            if (!root.isDirectory() && !root.mkdirs()) return false;
            File sentinel = new File(root, SENTINEL);
            if (!sentinel.exists()) {
                try (FileOutputStream output = new FileOutputStream(sentinel)) {
                    output.write(sentinelBytes());
                } catch (IOException failure) {
                    throw new IllegalStateException("Could not initialize synthetic sentinel", failure);
                }
            }
        }
        return true;
    }

    @Override public synchronized Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? ROOT_COLUMNS : projection);
        for (String id : Arrays.asList(NORMAL_ROOT, REFUSING_ROOT)) {
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : cursor.getColumnNames()) {
                Object value = null;
                switch (column) {
                    case Root.COLUMN_ROOT_ID: case Root.COLUMN_DOCUMENT_ID: value = id; break;
                    case Root.COLUMN_TITLE: value = title(id); break;
                    case Root.COLUMN_ICON: value = android.R.drawable.ic_menu_save; break;
                    case Root.COLUMN_FLAGS:
                        value = Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD;
                        break;
                    case Root.COLUMN_MIME_TYPES: value = "*/*"; break;
                    case Root.COLUMN_AVAILABLE_BYTES: value = storage.getUsableSpace(); break;
                }
                row.add(value);
            }
        }
        return cursor;
    }

    @Override public synchronized Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        MatrixCursor cursor = documents(projection);
        addDocument(cursor, documentId);
        cursor.setExtras(identityEvidence());
        return cursor;
    }

    @Override public synchronized Cursor queryChildDocuments(
            String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        File parent = file(parentDocumentId);
        if (!parent.isDirectory()) throw new FileNotFoundException("Not a synthetic directory");
        MatrixCursor cursor = documents(projection);
        File[] children = parent.listFiles();
        if (children != null) {
            Arrays.sort(children);
            for (File child : children) addDocument(cursor, parentDocumentId + "/" + child.getName());
        }
        // Read-only evidence, available only through the same granted synthetic tree.
        Bundle evidence = identityEvidence();
        evidence.putStringArrayList(CREATED_IDS, underRoot(createdIds, parentDocumentId));
        evidence.putStringArrayList(DELETED_IDS, underRoot(deletedIds, parentDocumentId));
        evidence.putStringArrayList(REFUSED_IDS, underRoot(refusedIds, parentDocumentId));
        cursor.setExtras(evidence);
        cursor.setNotificationUri(getContext().getContentResolver(),
                DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId));
        return cursor;
    }

    @Override public synchronized String createDocument(String parentDocumentId, String mimeType,
            String displayName) throws FileNotFoundException {
        File parent = file(parentDocumentId);
        if (!parent.isDirectory() || displayName == null || displayName.isEmpty()
                || displayName.contains("/") || displayName.contains("\\")
                || displayName.equals(".") || displayName.equals("..")
                || displayName.equals(SENTINEL)
                || (parentDocumentId.contains("/") && Document.MIME_TYPE_DIR.equals(mimeType))) {
            throw new FileNotFoundException("Only new synthetic files are supported");
        }
        String candidate = displayName;
        int suffix = 1;
        while (new File(parent, candidate).exists()) candidate = displayName + "." + suffix++;
        String id = parentDocumentId + "/" + candidate;
        try {
            File created = new File(parent, candidate);
            if (Document.MIME_TYPE_DIR.equals(mimeType)) {
                if (!created.mkdir()) throw new IOException("Already exists");
                try (FileOutputStream output = new FileOutputStream(new File(created, SENTINEL))) {
                    output.write(sentinelBytes());
                }
            } else if (!created.createNewFile()) {
                throw new IOException("Already exists");
            }
        } catch (IOException failure) {
            throw new FileNotFoundException("Could not create synthetic document: " + failure);
        }
        createdIds.add(id);
        return id;
    }

    @Override public synchronized ParcelFileDescriptor openDocument(
            String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (signal != null) signal.throwIfCanceled();
        File document = file(documentId);
        if (document.getName().equals(SENTINEL) && !mode.equals("r")) {
            throw new FileNotFoundException("The unrelated sentinel is read-only");
        }
        return ParcelFileDescriptor.open(document, ParcelFileDescriptor.parseMode(mode));
    }

    @Override public synchronized void deleteDocument(String documentId) throws FileNotFoundException {
        File document = file(documentId);
        if (!documentId.contains("/") || document.getName().equals(SENTINEL)) {
            throw new FileNotFoundException("The fixture root and unrelated sentinel cannot be deleted");
        }
        if (document.isDirectory()) {
            // Test teardown may remove its own completed tree, after checking the sentinel.
            String[] children = document.list();
            if (children == null || children.length != 1 || !children[0].equals(SENTINEL)
                    || !new File(document, SENTINEL).delete() || !document.delete()) {
                throw new FileNotFoundException("Synthetic tree still contains saved documents");
            }
            deletedIds.add(documentId);
            return;
        }
        if (documentId.startsWith(REFUSING_ROOT + "/") && document.length() > 0) {
            refusedIds.add(documentId);
            throw new UnsupportedOperationException("Synthetic provider refuses nonempty document deletion");
        }
        // Refusal-case teardown can explicitly truncate its own synthetic partial before deleting.
        // Production cleanup never truncates an incomplete document to hide a failed delete.
        if (!document.delete()) throw new FileNotFoundException("Could not delete synthetic document");
        deletedIds.add(documentId);
    }

    @Override public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            File parent = file(parentDocumentId);
            File child = file(documentId);
            return parent.isDirectory() && child.getCanonicalPath().startsWith(parent.getCanonicalPath() + "/");
        } catch (IOException failure) {
            return false;
        }
    }

    private File file(String id) throws FileNotFoundException {
        if (id == null) throw new FileNotFoundException("Missing synthetic document ID");
        String[] parts = id.split("/", -1);
        if (parts.length < 1 || parts.length > 3
                || !(NORMAL_ROOT.equals(parts[0]) || REFUSING_ROOT.equals(parts[0]))) {
            throw new FileNotFoundException("Outside the synthetic fixture");
        }
        for (int index = 1; index < parts.length; index++) {
            if (parts[index].isEmpty() || parts[index].equals(".")
                    || parts[index].equals("..") || parts[index].contains("\\")) {
                throw new FileNotFoundException("Invalid synthetic document ID");
            }
        }
        File result = new File(storage, id);
        if (!result.exists()) throw new FileNotFoundException("Synthetic document does not exist");
        return result;
    }

    private MatrixCursor documents(String[] projection) {
        return new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
    }

    private void addDocument(MatrixCursor cursor, String id) throws FileNotFoundException {
        File document = file(id);
        boolean directory = document.isDirectory();
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            Object value = null;
            switch (column) {
                case Document.COLUMN_DOCUMENT_ID: value = id; break;
                case Document.COLUMN_DISPLAY_NAME:
                    value = directory && !id.contains("/") ? title(id) : document.getName();
                    break;
                case Document.COLUMN_MIME_TYPE:
                    value = directory ? Document.MIME_TYPE_DIR : "application/octet-stream";
                    break;
                case Document.COLUMN_FLAGS:
                    value = directory ? Document.FLAG_DIR_SUPPORTS_CREATE
                            : document.getName().equals(SENTINEL) ? 0
                            : Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE;
                    break;
                case Document.COLUMN_SIZE: value = document.length(); break;
                case Document.COLUMN_LAST_MODIFIED: value = document.lastModified(); break;
            }
            row.add(value);
        }
    }

    private ArrayList<String> underRoot(List<String> source, String root) {
        ArrayList<String> result = new ArrayList<>();
        for (String id : source) if (id.startsWith(root + "/")) result.add(id);
        return result;
    }

    private Bundle identityEvidence() {
        Bundle evidence = new Bundle();
        evidence.putInt(PROVIDER_UID, Process.myUid());
        evidence.putInt(CALLER_UID, Binder.getCallingUid());
        return evidence;
    }

    private String title(String id) {
        return NORMAL_ROOT.equals(id) ? NORMAL_TITLE : REFUSING_TITLE;
    }
}
