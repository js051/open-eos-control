package androidx.test.runner;
import android.os.Bundle;
import android.content.Context;
/** JVM-only delegate spy, excluded from all Android source sets. */
public class AndroidJUnitRunner {
    public static final Context targetContext = new Context();
    public static final Context instrumentationContext = new Context();
    public Context getTargetContext() { return targetContext; }
    public Context getContext() { return instrumentationContext; }
    public static Object seenObject;
    public static Throwable seenFailure;
    public static Bundle finishResults;
    public static int finishCode;
    public static RuntimeException throwFromDelegate;
    public void onStart() {}
    public boolean onException(Object object, Throwable failure) {
        seenObject = object; seenFailure = failure;
        if (throwFromDelegate != null) throw throwFromDelegate;
        return true;
    }
    public void finish(int code, Bundle results) { finishCode = code; finishResults = results; }
}
