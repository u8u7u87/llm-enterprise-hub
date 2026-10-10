package com.enterprise.llm.client;

import java.util.concurrent.atomic.AtomicBoolean;

public class StreamCancellationHandle {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Runnable cancelAction;

    public StreamCancellationHandle(Runnable cancelAction) {
        this.cancelAction = cancelAction;
    }

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            if (cancelAction != null) {
                cancelAction.run();
            }
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }
}
