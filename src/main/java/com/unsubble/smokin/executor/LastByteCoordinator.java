package com.unsubble.smokin.executor;

import java.io.IOException;

public class LastByteCoordinator {

    private final int totalParties;
    private int arrived = 0;
    private boolean broken = false;
    private Throwable failureCause = null;
    private final Object lock = new Object();

    public LastByteCoordinator(int totalParties) {
        if (totalParties <= 0) {
            throw new IllegalArgumentException("totalParties must be positive");
        }
        this.totalParties = totalParties;
    }

    public void await() throws IOException {
        synchronized (lock) {
            if (broken) {
                throw createAbortedException();
            }

            arrived++;
            if (arrived == totalParties) {
                lock.notifyAll();
                return;
            }

            while (arrived < totalParties && !broken) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    abort(e);
                    throw new IOException("Last-byte synchronization interrupted", e);
                }
            }

            if (broken) {
                throw createAbortedException();
            }
        }
    }

    public void abort(Throwable cause) {
        synchronized (lock) {
            if (!broken) {
                broken = true;
                failureCause = cause;
                lock.notifyAll();
            }
        }
    }

    public int getTotalParties() {
        return totalParties;
    }

    public int getArrived() {
        synchronized (lock) {
            return arrived;
        }
    }

    public boolean isBroken() {
        synchronized (lock) {
            return broken;
        }
    }

    public Throwable getFailureCause() {
        synchronized (lock) {
            return failureCause;
        }
    }

    private IOException createAbortedException() {
        String msg = "Last-byte synchronization aborted";
        if (failureCause != null && failureCause.getMessage() != null) {
            msg += ": " + failureCause.getMessage();
        }
        return new IOException(msg, failureCause);
    }
}
