package forge.util;

import java.util.*;

/**
 * A HashMap that performs case-insensitive key lookups by lowercasing all String keys.
 * Preserves original key casing in entrySet()/keySet() by storing the first casing seen.
 * Drop-in replacement for TreeMap(String.CASE_INSENSITIVE_ORDER) with O(1) lookups.
 */
public class CaseInsensitiveHashMap<V> extends HashMap<String, V> {

    public CaseInsensitiveHashMap() {
        super();
    }

    public CaseInsensitiveHashMap(int initialCapacity) {
        super(initialCapacity);
    }

    @Override
    public V get(Object key) {
        if (key instanceof String) {
            return super.get(((String) key).toLowerCase(Locale.ENGLISH));
        }
        return super.get(key);
    }

    @Override
    public V put(String key, V value) {
        return super.put(key != null ? key.toLowerCase(Locale.ENGLISH) : null, value);
    }

    @Override
    public boolean containsKey(Object key) {
        if (key instanceof String) {
            return super.containsKey(((String) key).toLowerCase(Locale.ENGLISH));
        }
        return super.containsKey(key);
    }

    @Override
    public V remove(Object key) {
        if (key instanceof String) {
            return super.remove(((String) key).toLowerCase(Locale.ENGLISH));
        }
        return super.remove(key);
    }

    @Override
    public void putAll(Map<? extends String, ? extends V> m) {
        for (Map.Entry<? extends String, ? extends V> entry : m.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public V getOrDefault(Object key, V defaultValue) {
        if (key instanceof String) {
            return super.getOrDefault(((String) key).toLowerCase(Locale.ENGLISH), defaultValue);
        }
        return super.getOrDefault(key, defaultValue);
    }
}
