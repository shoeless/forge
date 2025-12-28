package forge.util.function;

/**
 * iOS-compatible backport of java.util.function.Consumer for RoboVM.
 */
@FunctionalInterface
public interface Consumer<T> {
    /**
     * Performs this operation on the given argument.
     */
    void accept(T t);

    /**
     * Returns a composed {@code Consumer} that performs, in sequence, this
     * operation followed by the {@code after} operation.
     */
    default Consumer<T> andThen(final Consumer<? super T> after) {
        if (after == null) {
            throw new NullPointerException();
        }
        final Consumer<T> self = this;
        return new Consumer<T>() {
            @Override
            public void accept(T t) {
                self.accept(t);
                after.accept(t);
            }
        };
    }
}
