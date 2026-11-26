package dev.yamc.mcp.client;

import dev.yamc.mcp.util.ClientThread;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/** Runs multi-tick jobs (move, mine) on the client thread and reports their outcome through a future. */
public final class Ticker {
    /** A job stepped once per client tick. */
    public interface Job<R> {
        /** Returns the result when finished, or null to run again next tick. */
        R step() throws Exception;

        /**
         * Runs exactly once when the job ends.
         *
         * @param completed true if {@link #step} returned a result; false if it threw or the waiting caller cancelled.
         */
        default void end(boolean completed) {}
    }

    private record Running<R>(Job<R> job, CompletableFuture<R> done) {
        /** Steps the job; returns true when it is over. */
        boolean tick() {
            if (done.isDone()) return true; // cancelled by the waiting caller
            try {
                R r = job.step();
                if (r == null) return false;
                done.complete(r);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
            return true;
        }

        boolean completed() {
            return done.isDone() && !done.isCompletedExceptionally();
        }
    }

    /** Only touched on the client thread. */
    private static final List<Running<?>> RUNNING = new ArrayList<>();

    private Ticker() {}

    /** Longest a job may run. Tools bound their own deadlines below this; the stdio bridge waits longer. */
    public static final long MAX_JOB_MS = 9 * 60_000L;

    /**
     * Runs {@code setup} on the client thread, then steps the job it returns every tick. Setup errors are thrown here.
     * The returned future is the job's cancellation handle and exists before setup is queued: if setup times out,
     * it is skipped, or (if it already registered the job) the job is cancelled before its first step.
     */
    public static <R> CompletableFuture<R> start(Callable<Job<R>> setup) throws Exception {
        CompletableFuture<R> done = new CompletableFuture<>();
        try {
            ClientThread.call(() -> {
                RUNNING.add(new Running<>(setup.call(), done));
                return null;
            });
        } catch (Exception e) {
            done.cancel(false);
            throw e;
        }
        return done;
    }

    /** {@link #start} and wait for the result. A job that fails or times out throws; on timeout it is cancelled. */
    public static <R> R run(Callable<Job<R>> setup, long timeoutMs) throws Exception {
        CompletableFuture<R> f = start(setup);
        try {
            return ClientThread.await(f, timeoutMs, "the action to finish");
        } finally {
            f.cancel(false);
        }
    }

    static void tick() {
        if (RUNNING.isEmpty()) return;
        for (Running<?> r : new ArrayList<>(RUNNING)) {
            if (!r.tick()) continue;
            RUNNING.remove(r);
            r.job().end(r.completed());
        }
    }
}
