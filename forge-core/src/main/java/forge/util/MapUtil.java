package forge.util;

import java.util.Map;

/**
 * Utility methods for Map operations that are iOS-compatible.
 *
 * RoboVM/MobiVM on iOS doesn't support Java 8 Map methods like getOrDefault(),
 * so we provide compatible implementations here.
 */
public class MapUtil {

    /**
     * Returns the value to which the specified key is mapped, or defaultValue if the map contains no mapping for the key.
     *
     * iOS-compatible replacement for Map.getOrDefault() (Java 8).
     *
     * @param map the map to get the value from
     * @param key the key whose associated value is to be returned
     * @param defaultValue the default value to return if the key is not present
     * @return the value to which the specified key is mapped, or defaultValue if no mapping exists
     */
    public static <K, V> V getOrDefault(Map<K, V> map, K key, V defaultValue) {
        V value = map.get(key);
        return value != null ? value : defaultValue;
    }
}
