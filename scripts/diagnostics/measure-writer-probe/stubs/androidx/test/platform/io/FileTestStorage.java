package androidx.test.platform.io;
import android.net.Uri;
import java.io.*;
import java.nio.file.Path;
/** JVM-only provider spy. */
public final class FileTestStorage {
    public static Path root;
    public static boolean fail;
    public OutputStream openOutputFile(String name) throws IOException {
        if (fail) throw new IOException("synthetic storage failure");
        return new FileOutputStream(root.resolve(name).toFile());
    }
    public Uri getOutputFileUri(String name) { return Uri.fromFile(root.resolve(name).toFile()); }
}
