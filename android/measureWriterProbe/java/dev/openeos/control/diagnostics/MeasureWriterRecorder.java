package dev.openeos.control.diagnostics;

import android.os.Looper;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Local-only observer. Hooks do memory work only: no logging, file I/O, waiting or dispatch. */
public final class MeasureWriterRecorder {
    // Existing failure wrapper retains this tag. Prefix keeps probe output distinct from date diagnostics.
    public static final String TAG = "EOSDateFilterDiag";
    public static final String PREFIX = "EOSMeasureWriter ";
    public static final String MANIFEST = "compose-1.9.3;entries=3;classWrites=17;guards=4;probe=v2";
    private static final int THREADS = 32, EVENTS = 128, STACK_FRAMES = 12;
    private static final AtomicReferenceArray<Buffer> BUFFERS = new AtomicReferenceArray<>(THREADS);
    private static final AtomicInteger NEXT_BUFFER = new AtomicInteger();
    private static final AtomicInteger STACK_CAPTURES = new AtomicInteger();
    private static final AtomicBoolean ENTRY_CLAIMED = new AtomicBoolean();
    private static final AtomicBoolean WRITER_CLAIMED = new AtomicBoolean();
    private static final AtomicBoolean GUARD_CLAIMED = new AtomicBoolean();
    private static final ThreadLocal<Buffer> LOCAL = new ThreadLocal<>();
    private static volatile Trigger firstEntry, firstWriter, firstGuard;
    private static volatile String manifest;
    private static volatile boolean broken;

    private MeasureWriterRecorder() {}

    public static void applied(String value) {
        // No logger/class-initialization I/O. The runner exports this stamp outside layout execution.
        manifest = value;
        if (!MANIFEST.equals(value)) broken = true;
    }
    public static void entry(Object delegate, Object root, boolean observed, String method) {
        try {
            Event event = record("ENTRY", delegate, root, observed, observed, method);
            if (event != null && !event.main && ENTRY_CLAIMED.compareAndSet(false, true)) {
                firstEntry = new Trigger("FIRST_OFF_MAIN_ENTRY", event, caller());
            }
        } catch (Throwable ignored) { broken = true; }
    }
    public static void guard(Object delegate, Object root, boolean observed, String method) {
        try {
            Event event = record("GUARD", delegate, root, observed, observed, method);
            if (event != null && GUARD_CLAIMED.compareAndSet(false, true)) {
                Trigger trigger = new Trigger("FIRST_GUARD_FAILURE", event, caller());
                trigger.history = snapshotAll(); // Bounded references only, before original throw, no I/O.
                firstGuard = trigger;
            }
        } catch (Throwable ignored) { broken = true; }
    }
    public static void write(boolean intended, Object delegate, Object root, boolean observed,
                             String site, int phase) {
        try {
            Event event = record(phase == 0 ? "PRE" : "POST", delegate, root, observed, intended, site);
            if (event == null) return;
            // Capture a stack only on the first attempted non-main true write, BEFORE that write.
            if (phase == 0 && intended && !event.main && WRITER_CLAIMED.compareAndSet(false, true)) {
                firstWriter = new Trigger("FIRST_OFF_MAIN_WRITE", event, caller());
            }
            if (phase == 1) {
                completeWrite(firstWriter, event);
                completeWrite(firstEntry, event);
            }
        } catch (Throwable ignored) { broken = true; }
    }
    public static void exit(Object delegate, Object root, boolean observed, String method) {
        try {
            Event event = record("EXIT", delegate, root, observed, observed, method);
            completeExit(firstEntry, event);
            completeExit(firstWriter, event);
        } catch (Throwable ignored) { broken = true; }
    }
    private static void completeWrite(Trigger trigger, Event event) {
        if (trigger == null || !trigger.matches(event)) return;
        if (event.intended && trigger.trueWritten == null) trigger.trueWritten = event;
        if (!event.intended && trigger.trueWritten != null && trigger.resetWritten == null) {
            trigger.resetWritten = event;
            // The original false PUTFIELD has already executed. No snapshot/string/log work before it.
            trigger.tail = snapshot(LOCAL.get());
        }
    }
    private static void completeExit(Trigger trigger, Event event) {
        if (event == null || trigger == null || trigger.exit != null || !trigger.matches(event)) return;
        trigger.exit = event;
        // A no-work call may exit without writing. Do not call that writer evidence.
        if (!event.observed) trigger.tail = snapshot(LOCAL.get());
    }
    private static Event record(String phase, Object delegate, Object root, boolean observed,
                                boolean intended, String site) {
        Buffer buffer = LOCAL.get();
        if (buffer == null) {
            int slot = NEXT_BUFFER.getAndIncrement();
            if (slot >= THREADS) { broken = true; return null; }
            buffer = new Buffer(Thread.currentThread().getId());
            LOCAL.set(buffer);
            BUFFERS.set(slot, buffer);
        }
        long sequence = ++buffer.sequence;
        // Conservative latest-entry token: never pair a later invocation with an earlier trigger.
        // Nested entries invalidate an outer pairing rather than inventing an atomic call stack.
        if (phase.equals("ENTRY") || (phase.equals("PRE") && site.startsWith("setDuringMeasureLayout"))) {
            buffer.latestEntry = sequence;
        }
        Event event = new Event(sequence, System.nanoTime(), buffer.threadId, buffer.latestEntry,
                Thread.currentThread() == Looper.getMainLooper().getThread(), phase,
                delegate, root, observed, intended, site);
        buffer.events.set((int) ((sequence - 1) % EVENTS), event);
        if (phase.equals("ENTRY")) buffer.entries++;
        if (phase.equals("PRE")) buffer.pre++;
        if (phase.equals("POST")) buffer.post++;
        buffer.published = sequence;
        return event;
    }
    private static StackTraceElement[] caller() {
        STACK_CAPTURES.incrementAndGet();
        StackTraceElement[] stack = new Throwable().getStackTrace();
        int start = 0;
        while (start < stack.length && stack[start].getClassName().equals(MeasureWriterRecorder.class.getName())) start++;
        return Arrays.copyOfRange(stack, start, Math.min(stack.length, start + STACK_FRAMES));
    }
    private static Snapshot[] snapshotAll() {
        Snapshot[] result = new Snapshot[THREADS];
        for (int i = 0; i < THREADS; i++) {
            Buffer buffer = BUFFERS.get(i);
            if (buffer != null) result[i] = snapshot(buffer);
        }
        return result;
    }
    private static Snapshot snapshot(Buffer buffer) {
        long end = buffer.published, start = Math.max(1, end - EVENTS + 1);
        Event[] events = new Event[(int) Math.max(0, end - start + 1)];
        for (int i = 0; i < events.length; i++) {
            long sequence = start + i;
            Event event = buffer.events.get((int) ((sequence - 1) % EVENTS));
            if (event != null && event.sequence == sequence) events[i] = event;
        }
        return new Snapshot(buffer.threadId, start, events);
    }
    /** Called only by runner finish/onException, after execution or after the original throwable. */
    public static String report(String boundary) {
        Snapshot[] boundarySnapshot = snapshotAll(); // Freeze references before formatting/storage I/O.
        StringBuilder out = new StringBuilder();
        IdentityHashMap<Object, Integer> identities = new IdentityHashMap<>();
        out.append("EXPECTED ").append(MANIFEST).append('\n');
        out.append("APPLIED ").append(manifest).append('\n');
        out.append(hasRuntimeProof() ? "PROOF_VALID " : "PROOF_INVALID ").append(proof()).append('\n');
        out.append("BOUNDARY ").append(boundary).append(" ns=").append(System.nanoTime()).append('\n');
        appendTrigger(out, identities, firstEntry);
        appendTrigger(out, identities, firstWriter);
        appendTrigger(out, identities, firstGuard);
        for (Snapshot snapshot : boundarySnapshot) appendSnapshot(out, identities, "BOUNDARY_TAIL", snapshot);
        return out.append("REPORT_COMPLETE\n").toString();
    }
    private static void appendTrigger(StringBuilder out, IdentityHashMap<Object, Integer> ids, Trigger trigger) {
        if (trigger == null) return;
        out.append("BEGIN ").append(trigger.reason).append('\n');
        appendEvent(out, ids, "TRIGGER", trigger.origin);
        for (StackTraceElement frame : trigger.stack) {
            out.append("CALLER ").append(frame.getClassName()).append('.').append(frame.getMethodName())
                    .append(':').append(frame.getLineNumber()).append('\n');
        }
        out.append("WRITER_STATE attempted=").append(trigger.reason.equals("FIRST_OFF_MAIN_WRITE"))
                .append(" trueWriteCompleted=").append(trigger.trueWritten != null)
                .append(" falseWriteCompleted=").append(trigger.resetWritten != null)
                .append(" explicitExitObserved=").append(trigger.exit != null).append('\n');
        appendEvent(out, ids, "TRUE_WRITE", trigger.trueWritten);
        appendEvent(out, ids, "FALSE_WRITE", trigger.resetWritten);
        appendEvent(out, ids, "EXIT", trigger.exit);
        if (trigger.history != null) for (Snapshot s : trigger.history) appendSnapshot(out, ids, "GUARD_HISTORY", s);
        appendSnapshot(out, ids, "COMPLETION_TAIL", trigger.tail);
        out.append("END ").append(trigger.reason).append('\n');
    }
    private static void appendSnapshot(StringBuilder out, IdentityHashMap<Object, Integer> ids, String kind, Snapshot snapshot) {
        if (snapshot == null) return;
        out.append(kind).append(" tid=").append(snapshot.threadId).append(" first=").append(snapshot.first)
                .append(" count=").append(snapshot.events.length).append('\n');
        for (int i = 0; i < snapshot.events.length; i++) {
            Event event = snapshot.events[i];
            if (event == null) out.append("GAP tid=").append(snapshot.threadId).append(" seq=").append(snapshot.first + i).append('\n');
            else appendEvent(out, ids, "EVENT", event);
        }
    }
    private static void appendEvent(StringBuilder out, IdentityHashMap<Object, Integer> ids, String kind, Event event) {
        if (event == null) return;
        out.append(kind).append(" tid=").append(event.threadId).append(" seq=").append(event.sequence)
                .append(" latestEntry=").append(event.latestEntry).append(" ns=").append(event.nanoTime).append(" main=").append(event.main).append(" phase=").append(event.phase)
                .append(" delegate=").append(identity(ids, event.delegate)).append(" root=").append(identity(ids, event.root))
                .append(" observed=").append(event.observed).append(" intended=").append(event.intended)
                .append(" site=").append(event.site).append('\n');
    }
    private static String identity(IdentityHashMap<Object, Integer> ids, Object object) {
        if (object == null) return "null";
        Integer id = ids.get(object);
        if (id == null) { id = ids.size() + 1; ids.put(object, id); }
        return "d" + id + "/h" + Integer.toHexString(System.identityHashCode(object));
    }
    public static void invalidate() { broken = true; }
    public static String proof() {
        long entries = 0, pre = 0, post = 0;
        for (int i = 0; i < THREADS; i++) {
            Buffer b = BUFFERS.get(i);
            if (b != null) { long acquired = b.published; entries += b.entries; pre += b.pre; post += b.post; }
        }
        return "manifest=" + manifest + " entries=" + entries + " pre=" + pre + " post=" + post
                + " broken=" + broken + " buffers=" + NEXT_BUFFER.get() + " stackCaptures=" + STACK_CAPTURES.get();
    }
    public static boolean hasRuntimeProof() {
        if (broken || !MANIFEST.equals(manifest)) return false;
        for (int i = 0; i < THREADS; i++) {
            Buffer b = BUFFERS.get(i);
            if (b != null) { long acquired = b.published; if (b.entries > 0 && b.pre > 0 && b.post > 0) return true; }
        }
        return false;
    }
    private static final class Trigger {
        final String reason, method, writerPrefix;
        final Event origin;
        final StackTraceElement[] stack;
        volatile Event trueWritten, resetWritten, exit;
        volatile Snapshot tail;
        Snapshot[] history;
        Trigger(String reason, Event origin, StackTraceElement[] stack) {
            this.reason = reason; this.origin = origin; this.stack = stack;
            int hash = origin.site.lastIndexOf('#');
            method = hash < 0 ? origin.site : origin.site.substring(0, hash);
            writerPrefix = method + "#";
        }
        boolean matches(Event e) {
            return origin.threadId == e.threadId && origin.delegate == e.delegate
                    && origin.latestEntry == e.latestEntry
                    && (e.site.equals(method) || e.site.startsWith(writerPrefix));
        }
    }
    private static final class Snapshot {
        final long threadId, first;
        final Event[] events;
        Snapshot(long threadId, long first, Event[] events) { this.threadId = threadId; this.first = first; this.events = events; }
    }
    private static final class Buffer {
        final long threadId;
        final AtomicReferenceArray<Event> events = new AtomicReferenceArray<>(EVENTS);
        long sequence, entries, pre, post, latestEntry;
        volatile long published;
        Buffer(long threadId) { this.threadId = threadId; }
    }
    private static final class Event {
        final long sequence, nanoTime, threadId, latestEntry;
        final boolean main, observed, intended;
        final String phase, site;
        final Object delegate, root;
        Event(long sequence, long nanoTime, long threadId, long latestEntry, boolean main, String phase,
              Object delegate, Object root, boolean observed, boolean intended, String site) {
            this.sequence = sequence; this.nanoTime = nanoTime; this.threadId = threadId; this.latestEntry = latestEntry;
            this.main = main; this.phase = phase; this.delegate = delegate; this.root = root;
            this.observed = observed; this.intended = intended; this.site = site;
        }
    }
}
