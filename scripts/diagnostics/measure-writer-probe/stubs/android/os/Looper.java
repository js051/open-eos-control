package android.os;
/** JVM-only platform stand-in; never included in an Android source set. */
public final class Looper {
    private static final Looper MAIN = new Looper(Thread.currentThread());
    private final Thread thread;
    private Looper(Thread thread) { this.thread = thread; }
    public static Looper getMainLooper() { return MAIN; }
    public Thread getThread() { return thread; }
}
