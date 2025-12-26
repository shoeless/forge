package forge.util;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import forge.util.function.Function;
import forge.util.function.Predicate;

/**
 * Provides helper methods for Iterables and Predicates similar
 * to the Guava library, but supporting custom Predicate implementation
 * compatible with iOS/RoboVM (no Stream API).
 */
public final class IterableUtil {

    private IterableUtil() {
        // Utility class - no instantiation
    }

    /**
     * Merges predicates into one requiring all to match.
     *
     * @param <T> the type being tested
     * @param components the predicates to merge
     * @return combined predicate
     */
    public static <T> Predicate<T> and(
            final Iterable<? extends Predicate<? super T>> components) {
        if (components instanceof List
                && ((List<?>) components).size() == 1) {
            return ((List<? extends Predicate<? super T>>)
                    components).get(0)::test;
        }
        return x -> all(components, i -> i.test(x));
    }

    /**
     * Merges predicates into one requiring at least one to match.
     *
     * @param <T> the type being tested
     * @param components the predicates to merge
     * @return combined predicate
     */
    public static <T> Predicate<T> or(
            final Iterable<? extends Predicate<? super T>> components) {
        if (components instanceof List
                && ((List<?>) components).size() == 1) {
            return ((List<? extends Predicate<? super T>>)
                    components).get(0)::test;
        }
        return x -> any(components, i -> i.test(x));
    }

    /**
     * Returns lazy iterable of filtered elements.
     *
     * @param <T> element type
     * @param iterable source iterable
     * @param filter predicate to test elements
     * @return filtered iterable
     */
    public static <T> Iterable<T> filter(final Iterable<T> iterable,
            final Predicate<? super T> filter) {
        return () -> new FilterIterator<>(iterable.iterator(), filter);
    }

    /**
     * Returns lazy iterable of filtered elements.
     *
     * @param <T> element type
     * @param iterable source collection
     * @param filter predicate to test elements
     * @return filtered iterable
     */
    public static <T> Iterable<T> filter(final Collection<T> iterable,
            final Predicate<? super T> filter) {
        return () -> new FilterIterator<>(iterable.iterator(), filter);
    }

    /**
     * Returns lazy iterable of elements of desired type.
     *
     * @param <T> desired type
     * @param iterable source iterable
     * @param desiredType class to filter by
     * @return filtered iterable
     */
    public static <T> Iterable<T> filter(final Iterable<?> iterable,
            final Class<T> desiredType) {
        return () -> new TypeFilterIterator<>(iterable.iterator(),
                desiredType);
    }

    /**
     * Tests if any element matches predicate.
     *
     * @param <T> element type
     * @param iterable source iterable
     * @param test predicate to test
     * @return true if any match
     */
    public static <T> boolean any(final Iterable<T> iterable,
            final Predicate<? super T> test) {
        for (T item : iterable) {
            if (test.test(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tests if all elements match predicate.
     *
     * @param <T> element type
     * @param iterable source iterable
     * @param test predicate to test
     * @return true if all match
     */
    public static <T> boolean all(final Iterable<T> iterable,
            final Predicate<? super T> test) {
        for (T item : iterable) {
            if (!test.test(item)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Finds first matching element.
     *
     * @param <T> element type
     * @param iterable source iterable
     * @param predicate predicate to match
     * @return first match or null
     */
    public static <T> T find(final Iterable<T> iterable,
            final Predicate<? super T> predicate) {
        for (T item : iterable) {
            if (predicate.test(item)) {
                return item;
            }
        }
        return null;
    }

    /**
     * Finds first matching element or returns default.
     *
     * @param <T> element type
     * @param iterable source iterable
     * @param predicate predicate to match
     * @param defaultValue value to return if not found
     * @return first match or defaultValue
     */
    public static <T> T find(final Iterable<T> iterable,
            final Predicate<? super T> predicate,
            final T defaultValue) {
        for (T item : iterable) {
            if (predicate.test(item)) {
                return item;
            }
        }
        return defaultValue;
    }

    /**
     * Tries to find first matching element.
     * Returns null if not found.
     *
     * @param <T> element type
     * @param iterable source iterable
     * @param predicate predicate to match
     * @return first match or null
     */
    public static <T> T tryFind(final Iterable<T> iterable,
            final Predicate<? super T> predicate) {
        for (T item : iterable) {
            if (predicate.test(item)) {
                return item;
            }
        }
        return null;
    }

    /**
     * Returns index of first matching element.
     *
     * @param <T> element type
     * @param iterable source iterable
     * @param predicate predicate to match
     * @return index or -1 if not found
     */
    public static <T> int indexOf(final Iterable<T> iterable,
            final Predicate<? super T> predicate) {
        int index = 0;
        for (T i : iterable) {
            if (predicate.test(i)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    /**
     * Returns lazy iterable of transformed elements.
     *
     * @param <F> source type
     * @param <T> target type
     * @param iterable source iterable
     * @param function transformation function
     * @return transformed iterable
     */
    public static <F, T> Iterable<T> transform(final Iterable<F> iterable,
            final Function<? super F, T> function) {
        return () -> new TransformIterator<>(iterable.iterator(), function);
    }

    // Helper iterator classes for lazy evaluation

    private static final class FilterIterator<T> implements Iterator<T> {
        private final Iterator<T> source;
        private final Predicate<? super T> predicate;
        private T next;
        private boolean hasNext;

        FilterIterator(final Iterator<T> source,
                final Predicate<? super T> predicate) {
            this.source = source;
            this.predicate = predicate;
            advance();
        }

        private void advance() {
            while (source.hasNext()) {
                T candidate = source.next();
                if (predicate.test(candidate)) {
                    next = candidate;
                    hasNext = true;
                    return;
                }
            }
            hasNext = false;
        }

        @Override
        public boolean hasNext() {
            return hasNext;
        }

        @Override
        public T next() {
            if (!hasNext) {
                throw new NoSuchElementException();
            }
            T result = next;
            advance();
            return result;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class TypeFilterIterator<T> implements Iterator<T> {
        private final Iterator<?> source;
        private final Class<T> type;
        private T next;
        private boolean hasNext;

        TypeFilterIterator(final Iterator<?> source, final Class<T> type) {
            this.source = source;
            this.type = type;
            advance();
        }

        private void advance() {
            while (source.hasNext()) {
                Object candidate = source.next();
                if (type.isInstance(candidate)) {
                    next = type.cast(candidate);
                    hasNext = true;
                    return;
                }
            }
            hasNext = false;
        }

        @Override
        public boolean hasNext() {
            return hasNext;
        }

        @Override
        public T next() {
            if (!hasNext) {
                throw new NoSuchElementException();
            }
            T result = next;
            advance();
            return result;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class TransformIterator<F, T>
            implements Iterator<T> {
        private final Iterator<F> source;
        private final Function<? super F, T> function;

        TransformIterator(final Iterator<F> source,
                final Function<? super F, T> function) {
            this.source = source;
            this.function = function;
        }

        @Override
        public boolean hasNext() {
            return source.hasNext();
        }

        @Override
        public T next() {
            return function.apply(source.next());
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }
}
