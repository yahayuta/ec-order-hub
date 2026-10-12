package io.github.yahayuta.orderhub.mall;

import java.time.Duration;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Retries a call with exponential backoff, but only for failures worth retrying (timeouts, 5xx,
 * 429). A 4xx such as a bad request fails at once: asking again will not fix it.
 */
public final class Retry {

    public record Outcome<T>(T value, int attempts) {
    }

    /** Thrown when every attempt failed; carries how many were made. */
    public static final class GaveUp extends RuntimeException {
        private final int attempts;

        GaveUp(int attempts, RuntimeException cause) {
            super("gave up after %d attempt(s): %s".formatted(attempts, cause.getMessage()), cause);
            this.attempts = attempts;
        }

        public int attempts() {
            return attempts;
        }
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private Retry() {
    }

    public static <T> Outcome<T> call(Supplier<T> action, int maxAttempts, Duration initialBackoff,
                                      Predicate<RuntimeException> retryable, Sleeper sleeper) {
        Duration backoff = initialBackoff;
        for (int attempt = 1; ; attempt++) {
            try {
                return new Outcome<>(action.get(), attempt);
            } catch (RuntimeException e) {
                if (attempt >= maxAttempts || !retryable.test(e)) {
                    throw new GaveUp(attempt, e);
                }
                try {
                    sleeper.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new GaveUp(attempt, e);
                }
                backoff = backoff.multipliedBy(2);
            }
        }
    }
}
