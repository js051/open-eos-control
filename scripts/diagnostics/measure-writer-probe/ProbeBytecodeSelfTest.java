import dev.openeos.probe.MeasureWriterVisitor;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Small local check against the ACTUAL pinned Compose class, not a copy of its source. */
public final class ProbeBytecodeSelfTest implements Opcodes {
    public static void main(String[] args) throws Exception {
        byte[] before;
        try (JarFile jar = new JarFile(args[0])) {
            before = jar.getInputStream(jar.getJarEntry(MeasureWriterVisitor.TARGET + ".class")).readAllBytes();
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(before).accept(new MeasureWriterVisitor(writer), 0);
        byte[] after = writer.toByteArray();
        Files.write(Path.of(args[1]), after);
        ClassNode original = read(before), transformed = read(after);
        int entries = 0, pre = 0, post = 0, guards = 0, stamps = 0, exits = 0;
        for (int m = 0; m < original.methods.size(); m++) {
            MethodNode a = original.methods.get(m), b = transformed.methods.get(m);
            new Analyzer<BasicValue>(new BasicVerifier()).analyze(transformed.name, b);
            Set<AbstractInsnNode> removed = Collections.newSetFromMap(new IdentityHashMap<>());
            for (AbstractInsnNode insn : b.instructions) {
                if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(MeasureWriterVisitor.RECORDER)) continue;
                switch (call.name) {
                    case "applied" -> { stamps++; removePrevious(removed, insn, 2); }
                    case "entry" -> { entries++; checkObserve(insn); removePrevious(removed, insn, 7); }
                    case "exit" -> { exits++; checkObserve(insn); removePrevious(removed, insn, 7); }
                    case "guard" -> { guards++; checkObserve(insn); removePrevious(removed, insn, 7); }
                    case "write" -> {
                        if (insn.getPrevious().getOpcode() == ICONST_0) {
                            pre++; removePrevious(removed, insn, 9);
                            check(insn.getNext().getOpcode() == DUP2, "missing original-write copy");
                            check(insn.getNext().getNext() instanceof FieldInsnNode f
                                    && f.getOpcode() == PUTFIELD && f.name.equals("duringMeasureLayout"), "original write lost");
                        } else {
                            post++; check(insn.getPrevious().getOpcode() == ICONST_1, "bad phase");
                            AbstractInsnNode first = removePrevious(removed, insn, 8);
                            check(first.getOpcode() == SWAP, "post prefix changed");
                            check(first.getPrevious().getOpcode() == PUTFIELD, "post not immediately after write");
                            AbstractInsnNode copy = first.getPrevious().getPrevious();
                            check(copy.getOpcode() == DUP2, "post receiver not preserved"); removed.add(copy);
                        }
                    }
                    default -> throw new AssertionError("unexpected hook " + call.name);
                }
            }
            for (AbstractInsnNode i : removed) b.instructions.remove(i);
            check(normalize(a).equals(normalize(b)), "original instructions/branches/handlers changed: " + a.name);
        }
        check(entries == 3 && pre == 17 && post == 17 && guards == 4 && stamps == 1, "incomplete hooks");
        // An AAR shape mismatch must abort the transform rather than silently instrument a subset.
        ClassNode drift = read(before);
        drift.methods.removeIf(m -> m.name.equals("measureOnly"));
        boolean rejected = false;
        try { drift.accept(new MeasureWriterVisitor(new ClassWriter(0))); }
        catch (IllegalStateException expected) { rejected = true; }
        check(rejected, "drift was not rejected");
        System.out.println("PASS actual Compose 1.9.3: entries=3 pre=17 post=17 guards=4 stamps=1");
        System.out.println("Observed explicit return/ATHROW exit hooks=" + exits);
        System.out.println("PASS BasicVerifier all methods; exact original instructions/branches/exception tables retained");
        System.out.println("PASS missing-entry mutation rejected (fail-closed shape validation)");
    }
    static void checkObserve(AbstractInsnNode call) {
        AbstractInsnNode first = call;
        for (int i = 0; i < 6; i++) first = first.getPrevious();
        check(first.getOpcode() == ALOAD && first.getNext().getOpcode() == ALOAD, "bad observer prefix");
    }
    static AbstractInsnNode removePrevious(Set<AbstractInsnNode> removed, AbstractInsnNode last, int count) {
        AbstractInsnNode first = last;
        for (int i = 0; i < count; i++) {
            check(first != null && first.getOpcode() >= 0, "hook crosses original label/frame");
            removed.add(first);
            if (i < count - 1) first = first.getPrevious();
        }
        return first;
    }
    static ClassNode read(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
    static List<String> normalize(MethodNode method) {
        Map<LabelNode, Integer> labels = new IdentityHashMap<>();
        int index = 0;
        for (AbstractInsnNode i : method.instructions) {
            if (i instanceof LabelNode label) labels.put(label, index);
            if (i.getOpcode() >= 0) index++;
        }
        List<String> result = new ArrayList<>();
        for (AbstractInsnNode i : method.instructions) {
            if (i.getOpcode() < 0) continue;
            String s = String.valueOf(i.getOpcode());
            if (i instanceof VarInsnNode v) s += ":" + v.var;
            else if (i instanceof IntInsnNode v) s += ":" + v.operand;
            else if (i instanceof FieldInsnNode v) s += ":" + v.owner + ":" + v.name + ":" + v.desc;
            else if (i instanceof MethodInsnNode v) s += ":" + v.owner + ":" + v.name + ":" + v.desc + ":" + v.itf;
            else if (i instanceof LdcInsnNode v) s += ":" + v.cst;
            else if (i instanceof TypeInsnNode v) s += ":" + v.desc;
            else if (i instanceof JumpInsnNode v) s += ":" + labels.get(v.label);
            else if (i instanceof IincInsnNode v) s += ":" + v.var + ":" + v.incr;
            else if (i instanceof TableSwitchInsnNode v) s += ":" + v.min + ":" + v.max + ":" + labels.get(v.dflt) + ":" + v.labels.stream().map(labels::get).toList();
            else if (i instanceof LookupSwitchInsnNode v) s += ":" + v.keys + ":" + labels.get(v.dflt) + ":" + v.labels.stream().map(labels::get).toList();
            else if (i instanceof InvokeDynamicInsnNode v) s += ":" + v.name + ":" + v.desc + ":" + v.bsm + ":" + Arrays.toString(v.bsmArgs);
            else if (i instanceof MultiANewArrayInsnNode v) s += ":" + v.desc + ":" + v.dims;
            else check(i instanceof InsnNode, "unhandled instruction " + i.getClass());
            result.add(s);
        }
        for (TryCatchBlockNode t : method.tryCatchBlocks) result.add("TRY:" + labels.get(t.start) + ":" + labels.get(t.end) + ":" + labels.get(t.handler) + ":" + t.type);
        return result;
    }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
