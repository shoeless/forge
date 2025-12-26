package forge.util.function;

/**
 * iOS-compatible backport of java.util.function.BiFunction for RoboVM.
 */
@FunctionalInterface
public interface BiFunction<T, U, R> {
    /**
     * Applies this function to the given arguments.
     */
    R apply(T t, U u);

    /**
     * Returns a composed function that first applies this function to
     * its input, and then applies the {@code after} function to the result.
     */
    default <V> BiFunction<T, U, V> andThen(Function<? super R, ? extends V> after) {
        if (after == null) {
            throw new NullPointerException();
        }
        return (t, u) -> after.apply(apply(t, u));
    }
}
