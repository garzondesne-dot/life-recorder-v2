package com.lifeblackbox.recorder;

/** Crash-safe publication order. A known destination is never replaced by a new one. */
final class RecordingPublication {
    static final class Target {
        final String uri;
        final boolean pending;
        Target(String uri, boolean pending) { this.uri=uri; this.pending=pending; }
    }
    interface Backend {
        boolean done() throws Exception;
        String destination() throws Exception;
        void prepare() throws Exception;
        Target find() throws Exception;
        Target inspect(String uri) throws Exception;
        Target create() throws Exception;
        void remember(String uri) throws Exception;
        void writePending(Target target) throws Exception;
        void finish(Target target) throws Exception;
        void index(Target target) throws Exception;
        void complete() throws Exception;
        void cleanup() throws Exception;
    }
    static void publish(Backend b) throws Exception {
        if(b.done()) { b.cleanup(); return; }
        String uri=b.destination();
        b.prepare(); // durable intent precedes MediaStore insertion
        Target target=uri==null ? b.find() : b.inspect(uri);
        if(target==null) {
            if(uri!=null) throw new java.io.IOException("저장 대상이 없어 복구를 보류합니다");
            target=b.create();
        }
        b.remember(target.uri);
        // Only an unfinished, app-owned destination may be written again.
        if(target.pending) { b.writePending(target); b.finish(target); }
        b.index(target); // idempotent by URI
        b.complete(); // receipt precedes journal cleanup
        b.cleanup();
    }
}

