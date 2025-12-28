package forge.util.function;

/**
 * iOS-compatible backport of java.util.function.Function for RoboVM.
 */
@FunctionalInterface
public interface Function<T, R> {
    /**
     * Applies this function to the given argument.
     */
    R apply(T t);

    /**
     * Returns a composed function that first applies the {@code before}
     * function to its input, and then applies this function to the result.
     */
    default <V> Function<V, R> compose(final Function<? super V, ? extends T> before) {
        if (before == null) {
            throw new NullPointerException();
        }
        final Function<T, R> self = this;
        return new Function<V, R>() {
            @Override
            public R apply(V v) {
                return self.apply(before.apply(v));
            }
        };
    }

    /**
     * Returns a composed function that first applies this function to
     * its input, and then applies the {@code after} function to the result.
     */
    default <V> Function<T, V> andThen(final Function<? super R, ? extends V> after) {
        if (after == null) {
            throw new NullPointerException();
        }
        final Function<T, R> self = this;
        return new Function<T, V>() {
            @Override
            public V apply(T t) {
                return after.apply(self.apply(t));
            }
        };
    }

    /**
     * Returns a function that always returns its input argument.
     */
    static <T> Function<T, T> identity() {
        return t -> t;
    }
}
