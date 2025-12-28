package forge.util.function;

/**
 * iOS-compatible backport of java.util.function.Predicate for RoboVM.
 * Mimics Java 8's Predicate API using language features (lambdas, default methods)
 * that RoboVM supports, avoiding standard library classes that RoboVM doesn't include.
 */
@FunctionalInterface
public interface Predicate<T> {
    /**
     * Evaluates this predicate on the given argument.
     */
    boolean test(T t);

    /**
     * Returns a composed predicate that represents a short-circuiting logical
     * AND of this predicate and another.
     */
    default Predicate<T> and(final Predicate<? super T> other) {
        if (other == null) {
            throw new NullPointerException();
        }
        final Predicate<T> self = this;
        return new Predicate<T>() {
            @Override
            public boolean test(T t) {
                return self.test(t) && other.test(t);
            }
        };
    }

    /**
     * Returns a predicate that represents the logical negation of this predicate.
     */
    default Predicate<T> negate() {
        final Predicate<T> self = this;
        return new Predicate<T>() {
            @Override
            public boolean test(T t) {
                return !self.test(t);
            }
        };
    }

    /**
     * Returns a composed predicate that represents a short-circuiting logical
     * OR of this predicate and another.
     */
    default Predicate<T> or(final Predicate<? super T> other) {
        if (other == null) {
            throw new NullPointerException();
        }
        final Predicate<T> self = this;
        return new Predicate<T>() {
            @Override
            public boolean test(T t) {
                return self.test(t) || other.test(t);
            }
        };
    }

    /**
     * Returns a predicate that tests if two arguments are equal according
     * to Objects.equals(Object, Object).
     */
    static <T> Predicate<T> isEqual(Object targetRef) {
        return (null == targetRef)
                ? object -> object == null
                : object -> targetRef.equals(object);
    }

    /**
     * Returns a predicate that is the negation of the supplied predicate.
     */
    static <T> Predicate<T> not(Predicate<? super T> target) {
        if (target == null) {
            throw new NullPointerException();
        }
        return t -> !target.test(t);
    }
}
