package forge.util.function;

/**
 * iOS-compatible backport of java.util.function.BinaryOperator for RoboVM.
 */
@FunctionalInterface
public interface BinaryOperator<T> extends BiFunction<T, T, T> {
    /**
     * Returns a {@link BinaryOperator} which returns the lesser of two elements
     * according to the specified {@code Comparator}.
     */
    static <T> BinaryOperator<T> minBy(java.util.Comparator<? super T> comparator) {
        if (comparator == null) {
            throw new NullPointerException();
        }
        return (a, b) -> comparator.compare(a, b) <= 0 ? a : b;
    }

    /**
     * Returns a {@link BinaryOperator} which returns the greater of two elements
     * according to the specified {@code Comparator}.
     */
    static <T> BinaryOperator<T> maxBy(java.util.Comparator<? super T> comparator) {
        if (comparator == null) {
            throw new NullPointerException();
        }
        return (a, b) -> comparator.compare(a, b) >= 0 ? a : b;
    }
}
