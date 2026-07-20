package forge.ai;

/**
 * Thread-scoped cooperative-cancellation deadline for AI decision evaluation.
 *
 * <p>On RoboVM/iOS {@code Thread.stop()} is a no-op, so a compute-bound AI decision thread cannot
 * be force-killed and a slow decision on a wide token/treasure board either leaks a pinned thread
 * (the async spell picker) or hard-hangs the game thread (inline combat declaration). The only way
 * to bound such a decision on RoboVM is for its OUTER loops to poll {@link #shouldAbort()} and bail
 * with a legal fallback.</p>
 *
 * <p>Usage: at a decision entry point call {@link #beginDecision(long)} with an absolute
 * {@link System#nanoTime()} deadline (ALWAYS paired with {@link #endDecision(long)} in a
 * {@code finally}), then call {@link #shouldAbort()} at outer-loop tops only &mdash; never inside
 * leaf helpers, a continuous-effect recompute, an LKI copy, or mid-mutation, where a partial result
 * would corrupt evaluation or leave illegal game state.</p>
 *
 * <p>When no deadline is set the value is {@link Long#MAX_VALUE}, so {@link #shouldAbort()} reduces
 * to the thread interrupt flag. Under deterministic-sim mode (-Dforge.rngSeed) callers pass
 * {@code Long.MAX_VALUE} and the async picker takes its no-timeout branch (never interrupting the
 * eval thread), so {@link #shouldAbort()} is provably always {@code false} and evaluation is
 * move-for-move identical to the un-guarded engine.</p>
 */
public final class AiDeadline {
    private AiDeadline() { }

    // NOT ThreadLocal.withInitial(...): that needs java.util.function.Supplier, which is absent on
    // RoboVM. An anonymous subclass overriding initialValue() is the RoboVM-safe equivalent.
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<Long>() {
        @Override protected Long initialValue() {
            return Long.MAX_VALUE;
        }
    };

    /**
     * Begin a decision bounded by the given absolute {@link System#nanoTime()} deadline. A nested
     * call keeps the tighter (minimum) of the current and new deadline, so combat evaluation nested
     * under the spell picker inherits the picker's budget rather than resetting to a looser one.
     *
     * @return the previous deadline, to be restored via {@link #endDecision(long)} in a finally.
     */
    public static long beginDecision(final long deadlineNanos) {
        final long prev = DEADLINE.get();
        DEADLINE.set(Math.min(prev, deadlineNanos));
        return prev;
    }

    /** Restore the deadline saved by {@link #beginDecision(long)}. ALWAYS call in a finally so a
     *  pooled worker thread never carries a stale deadline into an unrelated later task. */
    public static void endDecision(final long prev) {
        DEADLINE.set(prev);
    }

    /**
     * True if the current thread was interrupted or the active decision deadline has passed. Cheap
     * (one volatile read + one nanoTime); safe to poll at loop tops. NEVER call mid-mutation or
     * inside a leaf scan &mdash; bailing there yields a wrong value or illegal state.
     */
    public static boolean shouldAbort() {
        return Thread.currentThread().isInterrupted() || System.nanoTime() > DEADLINE.get();
    }
}
