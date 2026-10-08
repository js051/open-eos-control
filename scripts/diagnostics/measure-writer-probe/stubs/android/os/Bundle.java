package android.os;
import java.util.HashMap;
/** JVM-only runner-result stand-in. */
public final class Bundle {
    private final HashMap<String, String> values = new HashMap<>();
    public void putString(String name, String value) { values.put(name, value); }
    public String getString(String name, String fallback) { return values.getOrDefault(name, fallback); }
}
