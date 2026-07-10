package forge.ai;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public class AiCache {

    // stores result + args as vector
    private static Multimap<String, List<Object>> dataMap = Multimaps.synchronizedMultimap(HashMultimap.create());

    public static boolean identity(Object a, Object b) {
        return a == b;
    }

    // the cache is global for calculations that can be shared between games
    // but that also means unwanted collisions need to be considered:
    // for that you can pass Functions that compare the args
    public static <T> T getCached(String key, Supplier<T> func, List<BiFunction<Object, Object, Boolean>> argsCheck, Object... args) {
        // TODO would like a good strategy to derive default key, but there's no clean way to obtain the method name
        // dataMap is a Guava synchronizedMultimap: its collection views (get(key)) must be iterated while
        // holding the map's own lock, otherwise a concurrent put() below (e.g. from parallel DeckBattler
        // games sharing this global cache) mutates the backing collection mid-iteration -> CME. Snapshot
        // the view under the lock, then iterate/compute lock-free. func.get() is NOT run under the lock
        // (it can be expensive and re-enter getCached); a lost-update race just recomputes + double-inserts,
        // which the argsCheck read path tolerates.
        List<List<Object>> snapshot;
        synchronized (dataMap) {
            snapshot = Lists.newArrayList(dataMap.get(key));
        }
        for (List<Object> cached : snapshot) {
            boolean hit = true;
            for (int i = 0; i < args.length; i++) {
                BiFunction<Object, Object, Boolean> checker = argsCheck == null ? Object::equals : argsCheck.get(i);
                if (!checker.apply(args[i], cached.get(i + 1))) {
                    hit = false;
                    break;
                }
            }
            if (hit) {
                return (T) cached.get(0);
            }
        }
        T result = func.get();
        List<Object> cached = Lists.newArrayList(result);
        cached.addAll(Arrays.asList(args));
        synchronized (dataMap) {
            dataMap.put(key, cached);
        }
        return result;
    }

    public static void clear() {
        dataMap.clear();
    }

}
