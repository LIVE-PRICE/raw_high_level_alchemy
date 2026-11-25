package net.runelite.client.plugins.microbot.highlevelalchemy;

import java.util.ArrayDeque;
import java.util.Deque;

/** Allows transient UI recovery while stopping a genuinely broken retry loop. */
final class AlchemyFailureGuard {
    static final int TOLERATED_FAILURES = 6;
    static final long WINDOW_MS = 120_000L;

    static final class Decision {
        final int failures;
        final long backoffMillis;
        final boolean stop;

        Decision(int failures, long backoffMillis, boolean stop) {
            this.failures = failures;
            this.backoffMillis = backoffMillis;
            this.stop = stop;
        }
    }

    private final Deque<Long> failures = new ArrayDeque<>();

    Decision failure(long now) {
        while (!failures.isEmpty() && now - failures.peekFirst() > WINDOW_MS) failures.removeFirst();
        failures.addLast(now);
        int count = failures.size();
        long backoff = Math.min(10_000L, 1_500L * count);
        return new Decision(count, backoff, count > TOLERATED_FAILURES);
    }

    void success() {
        failures.clear();
    }

    void reset() {
        failures.clear();
    }
}
