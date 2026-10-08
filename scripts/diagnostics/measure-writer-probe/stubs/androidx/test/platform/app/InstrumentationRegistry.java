package androidx.test.platform.app;
import android.os.Bundle;
/** JVM-only configured collector argument, excluded from Android source sets. */
public final class InstrumentationRegistry {
    public static final Bundle arguments = new Bundle();
    static { arguments.putString("additionalTestOutputDir", "/synthetic/collector"); }
    public static Bundle getArguments() { return arguments; }
}
