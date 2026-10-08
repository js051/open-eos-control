package androidx.test.platform.io;
public final class PlatformTestStorageRegistry {
    private static final FileTestStorage STORAGE = new FileTestStorage();
    public static FileTestStorage getInstance() { return STORAGE; }
}
