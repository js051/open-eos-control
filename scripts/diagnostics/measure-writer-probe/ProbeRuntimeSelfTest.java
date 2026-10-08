import android.os.Looper;
import android.util.Log;
import dev.openeos.control.diagnostics.MeasureWriterRecorder;
import dev.openeos.probe.MeasureWriterVisitor;
import java.lang.reflect.*;
import org.objectweb.asm.*;

/** Executes injected code in a small synthetic writer, separately from the actual-class ASM audit. */
public final class ProbeRuntimeSelfTest implements Opcodes {
    public static void main(String[] args) throws Exception {
        Looper.getMainLooper(); // Initialize JVM main-thread stand-in on this thread.
        check(!MeasureWriterRecorder.hasRuntimeProof(), "missing hooks accepted");
        Loader loader = new Loader();
        loader.define("androidx.compose.ui.node.LayoutNode", empty("androidx/compose/ui/node/LayoutNode", false));
        loader.define("kotlin.jvm.functions.Function0", empty("kotlin/jvm/functions/Function0", true));
        ClassWriter transformed = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        new ClassReader(fixture()).accept(new MeasureWriterVisitor(transformed), 0);
        Class<?> type = loader.define(MeasureWriterVisitor.TARGET.replace('/', '.'), transformed.toByteArray());
        Object delegate = type.getConstructor().newInstance();
        Method run = type.getMethod("measureOnly"), setter = type.getMethod("setDuringMeasureLayout$ui_release", boolean.class);
        Field flag = type.getDeclaredField("duringMeasureLayout"); flag.setAccessible(true);
        Field mode = type.getField("mode");
        run.invoke(delegate);
        check(!(boolean) flag.get(delegate), "normal reset lost");
        mode.setInt(null, 1); run.invoke(delegate);
        check(!(boolean) flag.get(delegate), "handled reset lost");
        RuntimeException sentinel = new RuntimeException("synthetic-sentinel");
        type.getField("failure").set(null, sentinel); mode.setInt(null, 2);
        try { run.invoke(delegate); throw new AssertionError("original failure swallowed"); }
        catch (InvocationTargetException expected) { check(expected.getCause() == sentinel, "throwable identity changed"); }
        check(!(boolean) flag.get(delegate), "rethrow reset lost");
        setter.invoke(delegate, true);
        try { run.invoke(delegate); throw new AssertionError("guard swallowed"); }
        catch (InvocationTargetException expected) {
            check(expected.getCause() instanceof IllegalArgumentException
                    && MeasureWriterVisitor.GUARD.equals(expected.getCause().getMessage()), "guard changed");
        }
        check((boolean) flag.get(delegate), "guard changed layout state");
        setter.invoke(delegate, false); mode.setInt(null, 0);
        Thread other = new Thread(() -> { try { run.invoke(delegate); } catch (Exception e) { throw new AssertionError(e); } });
        other.start(); other.join(2000); check(!other.isAlive(), "worker did not terminate");
        check(MeasureWriterRecorder.hasRuntimeProof(), "executed hooks not proven");
        check(Log.lines.isEmpty(), "layout hooks performed log I/O");
        String evidence = MeasureWriterRecorder.report("synthetic-boundary");
        check(evidence.contains("BEGIN FIRST_GUARD_FAILURE"), "guard not retained");
        check(evidence.contains("BEGIN FIRST_OFF_MAIN_ENTRY"), "off-main entry not retained");
        check(evidence.contains("BEGIN FIRST_OFF_MAIN_WRITE"), "off-main writer not retained");
        check(evidence.contains("trueWriteCompleted=true falseWriteCompleted=true explicitExitObserved=true"), "completion tail lost");
        check(evidence.contains("phase=PRE") && evidence.contains("observed=false intended=true"), "bad PRE operands");
        check(evidence.contains("phase=POST") && evidence.contains("observed=true intended=true"), "bad POST operands");
        check(MeasureWriterRecorder.proof().contains("stackCaptures=3"), "rare stack capture count differs");
        for (int i = 0; i < 1000; i++) run.invoke(delegate);
        Class<?> recorder = MeasureWriterRecorder.class;
        Field buffers = recorder.getDeclaredField("BUFFERS"); buffers.setAccessible(true);
        Object all = buffers.get(null);
        check((int) all.getClass().getMethod("length").invoke(all) == 32, "unbounded registry");
        Object first = all.getClass().getMethod("get", int.class).invoke(all, 0);
        Field events = first.getClass().getDeclaredField("events"); events.setAccessible(true);
        Object ring = events.get(first);
        check((int) ring.getClass().getMethod("length").invoke(ring) == 128, "unbounded ring");
        check(MeasureWriterRecorder.proof().contains("stackCaptures=3"), "normal path captured additional stacks");
        check(Log.lines.isEmpty(), "ordinary layout emitted log I/O");
        String laterEvidence = MeasureWriterRecorder.report("synthetic-after-ring-overwrite");
        check(laterEvidence.contains("trueWriteCompleted=true falseWriteCompleted=true explicitExitObserved=true"), "pinned writer completion was overwritten");
        System.out.println("PASS injected fixture: normal/handled/rethrow resets; original throwable identity; guard preserved");
        System.out.println("PASS PRE/POST operands; pinned guard/off-main completion snapshots; no hot-path I/O/stacks; bounded rings; missing hooks rejected");
        System.out.println("SYNTHETIC_RUNTIME " + MeasureWriterRecorder.proof());
        for (int i = 0; i < 33; i++) {
            Thread extra = new Thread(() -> MeasureWriterRecorder.entry(delegate, null, false, "synthetic-overflow"));
            extra.start(); extra.join(2000); check(!extra.isAlive(), "overflow worker did not terminate");
        }
        check(!MeasureWriterRecorder.hasRuntimeProof(), "registry overflow silently accepted");
        System.out.println("PASS registry overflow invalidates diagnostic proof");
    }
    static byte[] empty(String name, boolean iface) {
        ClassWriter w = new ClassWriter(0); w.visit(V17, ACC_PUBLIC | (iface ? ACC_INTERFACE | ACC_ABSTRACT : 0), name, null, "java/lang/Object", null); w.visitEnd(); return w.toByteArray();
    }
    static byte[] fixture() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        String t = MeasureWriterVisitor.TARGET;
        w.visit(V17, ACC_PUBLIC, t, null, "java/lang/Object", null);
        w.visitField(ACC_PRIVATE, "root", MeasureWriterVisitor.ROOT_DESC, null, null).visitEnd();
        w.visitField(ACC_PRIVATE, "duringMeasureLayout", "Z", null, null).visitEnd();
        w.visitField(ACC_PUBLIC | ACC_STATIC, "mode", "I", null, null).visitEnd();
        w.visitField(ACC_PUBLIC | ACC_STATIC, "failure", "Ljava/lang/RuntimeException;", null, null).visitEnd();
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null); m.visitCode();
        m.visitVarInsn(ALOAD, 0); m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false); m.visitInsn(RETURN); end(m);
        m = w.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null); m.visitCode(); m.visitInsn(RETURN); end(m);
        m = w.visitMethod(ACC_PUBLIC, "setDuringMeasureLayout$ui_release", "(Z)V", null, null); m.visitCode();
        m.visitVarInsn(ALOAD, 0); m.visitVarInsn(ILOAD, 1); m.visitFieldInsn(PUTFIELD, t, "duringMeasureLayout", "Z"); m.visitInsn(RETURN); end(m);
        body(w, "measureAndLayout", "(Lkotlin/jvm/functions/Function0;)Z", true);
        body(w, "measureOnly", "()V", false);
        body(w, "measureAndLayout-0kLqBqw", "(" + MeasureWriterVisitor.ROOT_DESC + "J)V", false);
        body(w, "performMeasureAndLayout", "(ZLkotlin/jvm/functions/Function0;)V", false);
        w.visitEnd(); return w.toByteArray();
    }
    static void body(ClassWriter w, String name, String desc, boolean returnsBoolean) {
        String t = MeasureWriterVisitor.TARGET;
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, name, desc, null, null); m.visitCode(); Label okay = new Label();
        m.visitVarInsn(ALOAD, 0); m.visitFieldInsn(GETFIELD, t, "duringMeasureLayout", "Z"); m.visitJumpInsn(IFEQ, okay);
        m.visitTypeInsn(NEW, "java/lang/IllegalArgumentException"); m.visitInsn(DUP); m.visitLdcInsn(MeasureWriterVisitor.GUARD);
        m.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "(Ljava/lang/String;)V", false); m.visitInsn(ATHROW);
        m.visitLabel(okay); put(m, true); Label handled = new Label(), rethrow = new Label();
        m.visitFieldInsn(GETSTATIC, t, "mode", "I"); m.visitJumpInsn(IFNE, handled);
        put(m, false); ret(m, returnsBoolean); m.visitLabel(handled);
        m.visitFieldInsn(GETSTATIC, t, "mode", "I"); m.visitInsn(ICONST_1); m.visitJumpInsn(IF_ICMPNE, rethrow);
        put(m, false); ret(m, returnsBoolean); m.visitLabel(rethrow);
        put(m, false); m.visitFieldInsn(GETSTATIC, t, "failure", "Ljava/lang/RuntimeException;"); m.visitInsn(ATHROW); end(m);
    }
    static void put(MethodVisitor m, boolean b) { m.visitVarInsn(ALOAD, 0); m.visitInsn(b ? ICONST_1 : ICONST_0); m.visitFieldInsn(PUTFIELD, MeasureWriterVisitor.TARGET, "duringMeasureLayout", "Z"); }
    static void ret(MethodVisitor m, boolean b) { if (b) m.visitInsn(ICONST_1); m.visitInsn(b ? IRETURN : RETURN); }
    static void end(MethodVisitor m) { m.visitMaxs(0, 0); m.visitEnd(); }
    static void check(boolean v, String message) { if (!v) throw new AssertionError(message); }
    static final class Loader extends ClassLoader { Class<?> define(String name, byte[] b) { return defineClass(name, b, 0, b.length); } }
}
