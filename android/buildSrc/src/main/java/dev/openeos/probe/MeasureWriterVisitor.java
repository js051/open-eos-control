package dev.openeos.probe;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Observes direct field writes, including inline copies and finally resets, without replacing them. */
public final class MeasureWriterVisitor extends ClassVisitor implements Opcodes {
    public static final String TARGET = "androidx/compose/ui/node/MeasureAndLayoutDelegate";
    public static final String RECORDER = "dev/openeos/control/diagnostics/MeasureWriterRecorder";
    public static final String GUARD = "performMeasureAndLayout called during measure layout";
    public static final String ROOT_DESC = "Landroidx/compose/ui/node/LayoutNode;";
    private int entries, writes, guards, stamps;

    public MeasureWriterVisitor(ClassVisitor next) { super(ASM9, next); }

    @Override public MethodVisitor visitMethod(int access, String name, String desc,
                                               String signature, String[] exceptions) {
        MethodVisitor next = super.visitMethod(access, name, desc, signature, exceptions);
        boolean entry = (name.equals("measureAndLayout") && desc.equals("(Lkotlin/jvm/functions/Function0;)Z"))
                || (name.equals("measureOnly") && desc.equals("()V"))
                || (name.equals("measureAndLayout-0kLqBqw") && desc.equals("(" + ROOT_DESC + "J)V"));
        if (entry) entries++;
        return new MethodVisitor(ASM9, next) {
            int site;
            @Override public void visitCode() {
                super.visitCode();
                if (name.equals("<clinit>")) {
                    stamps++;
                    super.visitLdcInsn("compose-1.9.3;entries=3;classWrites=17;guards=4;probe=v2");
                    super.visitMethodInsn(INVOKESTATIC, RECORDER, "applied", "(Ljava/lang/String;)V", false);
                }
                if (entry) observe("entry", name + desc);
            }
            private void observe(String hook, String siteName) {
                super.visitVarInsn(ALOAD, 0);
                super.visitVarInsn(ALOAD, 0);
                super.visitFieldInsn(GETFIELD, TARGET, "root", ROOT_DESC);
                super.visitVarInsn(ALOAD, 0);
                super.visitFieldInsn(GETFIELD, TARGET, "duringMeasureLayout", "Z");
                super.visitLdcInsn(siteName);
                super.visitMethodInsn(INVOKESTATIC, RECORDER, hook,
                        "(Ljava/lang/Object;Ljava/lang/Object;ZLjava/lang/String;)V", false);
            }
            @Override public void visitInsn(int opcode) {
                if (entry && (opcode == IRETURN || opcode == RETURN || opcode == ATHROW)) {
                    // Keep the original return value / Throwable on the operand stack unchanged.
                    observe("exit", name + desc);
                }
                super.visitInsn(opcode);
            }
            @Override public void visitLdcInsn(Object value) {
                // This string is loaded only on the failing guard branch. Keep its throw helper intact.
                if (GUARD.equals(value)) { guards++; observe("guard", name + desc); }
                super.visitLdcInsn(value);
            }
            private void writeHook(String siteName, int phase) {
                // Input stack: receiver, intended value. Consume only that pair, not app operands below.
                super.visitInsn(SWAP);
                super.visitInsn(DUP);
                super.visitFieldInsn(GETFIELD, TARGET, "root", ROOT_DESC);
                super.visitVarInsn(ALOAD, 0);
                super.visitFieldInsn(GETFIELD, TARGET, "duringMeasureLayout", "Z");
                super.visitLdcInsn(siteName);
                super.visitInsn(phase == 0 ? ICONST_0 : ICONST_1);
                super.visitMethodInsn(INVOKESTATIC, RECORDER, "write",
                        "(ZLjava/lang/Object;Ljava/lang/Object;ZLjava/lang/String;I)V", false);
            }
            @Override public void visitFieldInsn(int opcode, String owner, String field, String descriptor) {
                if (opcode == PUTFIELD && owner.equals(TARGET) && field.equals("duringMeasureLayout")) {
                    if (!descriptor.equals("Z") || (access & ACC_STATIC) != 0) {
                        throw new IllegalStateException("Unexpected Compose writer shape; probe invalid");
                    }
                    String siteName = name + desc + "#" + (++site);
                    writes++;
                    super.visitInsn(DUP2);
                    writeHook(siteName, 0); // PRE: intended value and an extra read before the original write.
                    super.visitInsn(DUP2);
                    super.visitFieldInsn(opcode, owner, field, descriptor); // THE ORIGINAL WRITE.
                    writeHook(siteName, 1); // POST: completed original write, plus a later read (may differ).
                } else super.visitFieldInsn(opcode, owner, field, descriptor);
            }
        };
    }
    @Override public void visitEnd() {
        // The AAR has an unused private inline template: 17 class writes -> 13 in original DEX.
        // Fail closed on library drift rather than emit plausible-looking incomplete evidence.
        if (entries != 3 || writes != 17 || guards != 4 || stamps != 1) {
            throw new IllegalStateException("Compose probe shape mismatch: entries=" + entries
                    + " writes=" + writes + " guards=" + guards + " stamps=" + stamps);
        }
        super.visitEnd();
    }
}
