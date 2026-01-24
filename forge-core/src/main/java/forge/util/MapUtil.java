package forge.util;

import java.util.Map;

/**
 * Utility methods for Map operations that are iOS-compatible.
 *
 * RoboVM/MobiVM on iOS doesn't support Java 8 Map methods like
 * getOrDefault(), so we provide compatible implementations here.
 */
public final class MapUtil {

    /** Private constructor to prevent instantiation. */
    private MapUtil() {
    }

    /**
     * Returns the value to which the specified key is mapped,
     * or defaultValue if the map contains no mapping for the key.
     *
     * iOS-compatible replacement for Map.getOrDefault() (Java 8).
     *
     * @param <K> the type of keys maintained by the map
     * @param <V> the type of mapped values
     * @param map the map to get the value from
     * @param key the key whose associated value is to be returned
     * @param defaultValue the default value to return if key not present
     * @return the value to which the key is mapped, or defaultValue
     */
    public static <K, V> V getOrDefault(final Map<K, V> map,
            final K key, final V defaultValue) {
        V value = map.get(key);
        return value != null ? value : defaultValue;
    }

    /**
     * If the specified key is not already associated with a value,
     * attempts to compute its value using the given mapping function
     * and enters it into this map.
     *
     * iOS-compatible replacement for Map.computeIfAbsent() (Java 8).
     *
     * @param <K> the type of keys maintained by the map
     * @param <V> the type of mapped values
     * @param map the map to compute the value for
     * @param key the key with which the value is to be associated
     * @param mappingFunction the function to compute a value
     * @return the current (existing or computed) value for the key
     */
    public static <K, V> V computeIfAbsent(final Map<K, V> map,
            final K key,
            final forge.util.function.Function<? super K,
                    ? extends V> mappingFunction) {
        V value = map.get(key);
        if (value == null) {
            value = mappingFunction.apply(key);
            if (value != null) {
                map.put(key, value);
            }
        }
        return value;
    }
}
