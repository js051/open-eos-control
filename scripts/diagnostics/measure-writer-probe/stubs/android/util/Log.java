package android.util;
import java.util.ArrayList;
import java.util.List;
/** JVM-only sink; tests sequence/join their producers. Never shipped. */
public final class Log {
    public static final List<String> lines = new ArrayList<>();
    public static int i(String tag, String message) { lines.add(message); return 0; }
    public static int e(String tag, String message) { lines.add(message); return 0; }
}
