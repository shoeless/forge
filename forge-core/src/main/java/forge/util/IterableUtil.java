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
     * Tests if no elements match predicate.
     * iOS-compatible replacement for stream().noneMatch().
     *
     * @param <T> element type
     * @param iterable source iterable
     * @param test predicate to test
     * @return true if none match
     */
    public static <T> boolean none(final Iterable<T> iterable,
            final Predicate<? super T> test) {
        for (T item : iterable) {
            if (test.test(item)) {
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

    /**
     * Finds the maximum element according to the comparator.
     *
     * @param <T> element type
     * @param iterable source iterable
     * @param comparator comparator for ordering
     * @return maximum element or null if empty
     */
    public static <T> T max(final Iterable<T> iterable,
            final java.util.Comparator<? super T> comparator) {
        T max = null;
        for (T item : iterable) {
            if (max == null || comparator.compare(item, max) > 0) {
                max = item;
            }
        }
        return max;
    }

    /**
     * Finds the minimum element according to the comparator.
     *
     * @param <T> element type
     * @param iterable source iterable
     * @param comparator comparator for ordering
     * @return minimum element or null if empty
     */
    public static <T> T min(final Iterable<T> iterable,
            final java.util.Comparator<? super T> comparator) {
        T min = null;
        for (T item : iterable) {
            if (min == null || comparator.compare(item, min) < 0) {
                min = item;
            }
        }
        return min;
    }

    /**
     * Counts elements matching the predicate.
     *
     * @param <T> element type
     * @param iterable source iterable
     * @param predicate predicate to test
     * @return count of matching elements
     */
    public static <T> int count(final Iterable<T> iterable,
            final Predicate<? super T> predicate) {
        int count = 0;
        for (T item : iterable) {
            if (predicate.test(item)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Converts an iterable to a list.
     *
     * @param <T> element type
     * @param iterable source iterable
     * @return new ArrayList containing all elements
     */
    public static <T> java.util.List<T> toList(final Iterable<T> iterable) {
        java.util.List<T> result = new java.util.ArrayList<>();
        for (T item : iterable) {
            result.add(item);
        }
        return result;
    }

    /**
     * Filters elements and collects to a new list.
     * iOS-compatible replacement for stream().filter().collect(toList()).
     *
     * @param <T> element type
     * @param iterable source iterable
     * @param predicate predicate to test
     * @return new ArrayList containing matching elements
     */
    public static <T> java.util.List<T> filterToList(final Iterable<T> iterable,
            final Predicate<? super T> predicate) {
        java.util.List<T> result = new java.util.ArrayList<>();
        for (T item : iterable) {
            if (predicate.test(item)) {
                result.add(item);
            }
        }
        return result;
    }

    /**
     * Maps elements and collects to a new list.
     * iOS-compatible replacement for stream().map().collect(toList()).
     *
     * @param <F> source type
     * @param <T> target type
     * @param iterable source iterable
     * @param function transformation function
     * @return new ArrayList containing transformed elements
     */
    public static <F, T> java.util.List<T> mapToList(final Iterable<F> iterable,
            final Function<? super F, T> function) {
        java.util.List<T> result = new java.util.ArrayList<>();
        for (F item : iterable) {
            result.add(function.apply(item));
        }
        return result;
    }

    /**
     * Sums integer values extracted from elements.
     * iOS-compatible replacement for stream().mapToInt().sum().
     *
     * @param <T> element type
     * @param iterable source iterable
     * @param mapper function to extract int value
     * @return sum of all values
     */
    public static <T> int sumInt(final Iterable<T> iterable,
            final forge.util.function.Function<? super T, Integer> mapper) {
        int sum = 0;
        for (T item : iterable) {
            sum += mapper.apply(item);
        }
        return sum;
    }

    /**
     * Finds maximum integer value extracted from elements.
     * iOS-compatible replacement for stream().mapToInt().max().
     *
     * @param <T> element type
     * @param iterable source iterable
     * @param mapper function to extract int value
     * @return maximum value or Integer.MIN_VALUE if empty
     */
    public static <T> int maxInt(final Iterable<T> iterable,
            final forge.util.function.Function<? super T, Integer> mapper) {
        int max = Integer.MIN_VALUE;
        boolean found = false;
        for (T item : iterable) {
            int value = mapper.apply(item);
            if (!found || value > max) {
                max = value;
                found = true;
            }
        }
        return max;
    }

    /**
     * Finds minimum integer value extracted from elements.
     * iOS-compatible replacement for stream().mapToInt().min().
     *
     * @param <T> element type
     * @param iterable source iterable
     * @param mapper function to extract int value
     * @return minimum value or Integer.MAX_VALUE if empty
     */
    public static <T> int minInt(final Iterable<T> iterable,
            final forge.util.function.Function<? super T, Integer> mapper) {
        int min = Integer.MAX_VALUE;
        boolean found = false;
        for (T item : iterable) {
            int value = mapper.apply(item);
            if (!found || value < min) {
                min = value;
                found = true;
            }
        }
        return min;
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

    /**
     * iOS-compatible replacement for String.join() (Java 8 API not available in RoboVM).
     * Joins array elements with the specified delimiter.
     *
     * @param delimiter the delimiter to use between elements
     * @param elements the elements to join
     * @return the joined string
     */
    public static String join(CharSequence delimiter, CharSequence... elements) {
        if (elements == null || elements.length == 0) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < elements.length; i++) {
            if (i > 0) {
                result.append(delimiter);
            }
            result.append(elements[i]);
        }
        return result.toString();
    }

    /**
     * iOS-compatible replacement for String.join() (Java 8 API not available in RoboVM).
     * Joins iterable elements with the specified delimiter.
     *
     * @param delimiter the delimiter to use between elements
     * @param elements the elements to join
     * @return the joined string
     */
    public static String join(CharSequence delimiter, Iterable<? extends CharSequence> elements) {
        if (elements == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        boolean first = true;
        for (CharSequence element : elements) {
            if (!first) {
                result.append(delimiter);
            }
            result.append(element);
            first = false;
        }
        return result.toString();
    }

    /**
     * Joins elements from an iterable into a string using the specified delimiter.
     * iOS-compatible replacement for StringUtils.join() which uses Stream API.
     * Calls toString() on each element.
     *
     * @param delimiter the separator to use between elements
     * @param elements the iterable of elements to join
     * @return the joined string
     */
    public static String joinObjects(CharSequence delimiter, Iterable<?> elements) {
        if (elements == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        boolean first = true;
        for (Object element : elements) {
            if (!first) {
                result.append(delimiter);
            }
            if (element != null) {
                result.append(element.toString());
            }
            first = false;
        }
        return result.toString();
    }

    /**
     * Selects a random element from the iterable.
     * iOS-compatible replacement for stream().collect(StreamUtil.random()).get().
     *
     * @param <T> element type
     * @param iterable source iterable
     * @return random element or null if empty
     */
    public static <T> T random(final Iterable<T> iterable) {
        if (iterable == null) {
            return null;
        }
        // Use reservoir sampling algorithm for single item
        T result = null;
        int count = 0;
        for (T item : iterable) {
            count++;
            if (MyRandom.getRandom().nextInt(count) == 0) {
                result = item;
            }
        }
        return result;
    }

    /**
     * Selects multiple random elements from the iterable.
     * iOS-compatible replacement for stream().collect(StreamUtil.random(count)).
     *
     * @param <T> element type
     * @param iterable source iterable
     * @param count number of elements to select
     * @return list of randomly selected elements (may be fewer than count if iterable is smaller)
     */
    public static <T> java.util.List<T> random(final Iterable<T> iterable, final int count) {
        if (iterable == null || count <= 0) {
            return new java.util.ArrayList<>();
        }
        // Use reservoir sampling algorithm
        java.util.List<T> reservoir = new java.util.ArrayList<>(count);
        int sampleCount = 0;
        for (T item : iterable) {
            sampleCount++;
            if (sampleCount <= count) {
                reservoir.add(item);
            } else {
                int j = MyRandom.getRandom().nextInt(sampleCount);
                if (j < count) {
                    reservoir.set(j, item);
                }
            }
        }
        return reservoir;
    }

    /**
     * Sorts a list using the provided comparator.
     * iOS-compatible replacement for List.sort() which is a Java 8 default method not available on RoboVM.
     * This method uses Collections.sort() which is available in Java 7.
     *
     * @param <T> element type
     * @param list the list to sort (modified in place)
     * @param comparator the comparator to determine the order
     */
    public static <T> void sort(final java.util.List<T> list, final java.util.Comparator<T> comparator) {
        java.util.Collections.sort(list, comparator);
    }
}
