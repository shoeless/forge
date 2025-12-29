/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.util;

import forge.util.function.Function;
import forge.util.function.ToIntFunction;
import forge.util.function.ToLongFunction;

import java.util.Comparator;

/**
 * iOS-compatible utility methods for creating Comparators.
 * Replaces Java 8 Comparator factory methods (comparingInt, comparing, etc.)
 * which are not available on iOS/RoboVM.
 */
public final class ComparatorUtil {

    private ComparatorUtil() {
        throw new AssertionError();
    }

    /**
     * Returns a comparator that compares by extracting an int value.
     * iOS-compatible replacement for Comparator.comparingInt()
     */
    public static <T> Comparator<T> comparingInt(final ToIntFunction<? super T> keyExtractor) {
        return new Comparator<T>() {
            @Override
            public int compare(T a, T b) {
                return Integer.compare(keyExtractor.applyAsInt(a), keyExtractor.applyAsInt(b));
            }
        };
    }

    /**
     * Returns a comparator that compares by extracting a long value.
     * iOS-compatible replacement for Comparator.comparingLong()
     */
    public static <T> Comparator<T> comparingLong(final ToLongFunction<? super T> keyExtractor) {
        return new Comparator<T>() {
            @Override
            public int compare(T a, T b) {
                return Long.compare(keyExtractor.applyAsLong(a), keyExtractor.applyAsLong(b));
            }
        };
    }

    /**
     * Returns a comparator that compares by extracting a comparable value.
     * iOS-compatible replacement for Comparator.comparing()
     */
    public static <T, U extends Comparable<? super U>> Comparator<T> comparing(
            final Function<? super T, ? extends U> keyExtractor) {
        return new Comparator<T>() {
            @Override
            public int compare(T a, T b) {
                U ka = keyExtractor.apply(a);
                U kb = keyExtractor.apply(b);
                return ka.compareTo(kb);
            }
        };
    }

    /**
     * Returns a reversed comparator.
     * iOS-compatible replacement for Comparator.reversed()
     */
    public static <T> Comparator<T> reversed(final Comparator<T> comparator) {
        return new Comparator<T>() {
            @Override
            public int compare(T a, T b) {
                return comparator.compare(b, a);
            }
        };
    }

    /**
     * Returns a comparator that uses another comparator when the first compares equal.
     * iOS-compatible replacement for Comparator.thenComparing()
     */
    public static <T> Comparator<T> thenComparing(final Comparator<T> first, final Comparator<? super T> second) {
        return new Comparator<T>() {
            @Override
            public int compare(T a, T b) {
                int result = first.compare(a, b);
                return (result != 0) ? result : second.compare(a, b);
            }
        };
    }
}
