package forge.util.function;

/**
 * iOS-compatible backport of java.util.function.BiConsumer for RoboVM.
 */
@FunctionalInterface
public interface BiConsumer<T, U> {
    /**
     * Performs this operation on the given arguments.
     */
    void accept(T t, U u);

    /**
     * Returns a composed {@code BiConsumer} that performs, in sequence, this
     * operation followed by the {@code after} operation.
     */
    default BiConsumer<T, U> andThen(final BiConsumer<? super T, ? super U> after) {
        if (after == null) {
            throw new NullPointerException();
        }
        final BiConsumer<T, U> self = this;
        return new BiConsumer<T, U>() {
            @Override
            public void accept(T t, U u) {
                self.accept(t, u);
                after.accept(t, u);
            }
        };
    }
}
