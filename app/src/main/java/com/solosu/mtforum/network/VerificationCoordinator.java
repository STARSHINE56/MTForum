package com.solosu.mtforum.network;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** One global verification at a time; all waiting requests share its bounded result. */
public final class VerificationCoordinator {
    public interface Flow { void start(CompletableFuture<Boolean> result); }
    private final LongSupplier clock;
    private final long timeoutMillis;
    private final long cooldownMillis;
    private CompletableFuture<Boolean> active;
    private String activeKey;
    private long failedAt = Long.MIN_VALUE;

    public VerificationCoordinator() { this(System::currentTimeMillis, 95000, 60000); }
    VerificationCoordinator(LongSupplier clock, long timeoutMillis, long cooldownMillis) {
        this.clock = clock;
        this.timeoutMillis = timeoutMillis;
        this.cooldownMillis = cooldownMillis;
    }
    public boolean verify(Flow flow) { return verify("default", flow); }
    public boolean verify(String key, Flow flow) {
        // Different UAs may have separately bound clearance cookies. Wait for the current
        // browser, then run that context independently; never switch UA in a live WebView.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        for (int attempt = 0; attempt < 2; attempt++) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            Result outcome = await(key, flow, remaining);
            if (!outcome.passed || outcome.sameContext) return outcome.passed;
        }
        return false;
    }
    private static final class Result {
        final boolean passed;
        final boolean sameContext;
        Result(boolean passed, boolean sameContext) { this.passed = passed; this.sameContext = sameContext; }
    }
    private Result await(String key, Flow flow, long remainingNanos) {
        CompletableFuture<Boolean> result;
        boolean sameContext;
        boolean owner = false;
        synchronized (this) {
            if (active != null) result = active;
            else {
                if (failedAt != Long.MIN_VALUE && clock.getAsLong() - failedAt < cooldownMillis) return new Result(false, true);
                result = new CompletableFuture<>();
                active = result;
                activeKey = key;
                owner = true;
            }
            sameContext = key.equals(activeKey);
        }
        if (owner) {
            try { flow.start(result); }
            catch (RuntimeException error) { result.complete(false); }
        }
        try { return new Result(Boolean.TRUE.equals(result.get(remainingNanos, TimeUnit.NANOSECONDS)), sameContext); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); return new Result(false, sameContext); }
        catch (Exception error) { result.complete(false); return new Result(false, sameContext); }
        finally {
            synchronized (this) {
                if (active == result && result.isDone()) {
                    if (!Boolean.TRUE.equals(result.getNow(false))) failedAt = clock.getAsLong();
                    active = null;
                }
            }
        }
    }
}
