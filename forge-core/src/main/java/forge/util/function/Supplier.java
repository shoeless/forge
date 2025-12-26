package forge.util.function;

/**
 * iOS-compatible backport of java.util.function.Supplier for RoboVM.
 */
@FunctionalInterface
public interface Supplier<T> {
    /**
     * Gets a result.
     */
    T get();
}
