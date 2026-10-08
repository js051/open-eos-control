package android.content;
import android.content.pm.ApplicationInfo;
public final class Context {
    public final ApplicationInfo info = new ApplicationInfo();
    public ApplicationInfo getApplicationInfo() { return info; }
}
