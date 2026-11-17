package top.zainzh.rearscreen;

import java.util.concurrent.atomic.AtomicReference;

/** Lock-free receipt serializer: Binder callbacks never acquire the Cast monitor. */
final class PhysicalReaderReceiptGate {
    interface Persist { boolean write(String state, String id, long generation); }
    private final AtomicReference<String> state = new AtomicReference<>("NOT_REQUESTED");
    private volatile String readerId = "";
    private volatile long readerGeneration;

    String state() { return state.get(); }
    String readerId() { return readerId; }
    long readerGeneration() { return readerGeneration; }

    boolean record(String next, String id, long generation, Persist persist) {
        String exactId = id == null ? "" : id;
        String before = state.get();
        if (before.startsWith("PENDING:")) return false;
        if (!"SESSION_CREATED".equals(next) && before.equals(next)
                && readerId.equals(exactId) && readerGeneration == generation) return true;
        String pending = "PENDING:" + next;
        if (!state.compareAndSet(before, pending)) return false;
        boolean saved;
        try { saved = persist.write(next, exactId, generation); }
        catch (RuntimeException failure) { saved = false; }
        if (!saved) {
            state.compareAndSet(pending, before);
            return false;
        }
        readerId = exactId;
        readerGeneration = generation;
        state.compareAndSet(pending, "SESSION_CREATED".equals(next) ? before : next);
        return true;
    }
}
