package forge.assets;

import forge.Forge;

import java.lang.reflect.Method;

/**
 * Gated per-frame memory probe (memory-reduction work). Throttled to ~2s. Logs Java heap + the downloaded-card-
 * texture cache size + (on iOS) the bdwgc native heap/unmapped bytes + the continuous-render
 * refcount, so the on-device memory ratchet, the RSS returned to the OS after a match, and render-loop leaks are
 * all visible in os_log. Off in production unless {@code forge.memLog} is set or {@link #ENABLED} is forced on.
 */
public final class MemProbe {
    // Off in production; enable with -Dforge.memLog (or forge.memLog system property) for profiling.
    public static boolean ENABLED = System.getProperty("forge.memLog") != null;
    private static long lastLog = 0;

    // iOS-only bdwgc getters, resolved reflectively so desktop/Android still compile (class absent there).
    // getHeapSize() ~= the native heap bdwgc holds (an RSS proxy); getUnmappedBytes() = bytes returned to the OS.
    private static boolean gcProbed = false;
    private static Method mHeapSize, mUnmapped;

    private MemProbe() { }

    /** Restores the in-game bdwgc growth policy after the permissive boot setting (no-op off iOS). */
    public static void setGcFreeSpaceDivisor(long divisor) {
        try {
            Class<?> c = Class.forName("org.robovm.rt.GC");
            c.getMethod("setFreeSpaceDivisor", long.class).invoke(null, divisor);
        } catch (Throwable ignored) {
        }
    }

    private static void initGcGetters() {
        gcProbed = true;
        try {
            Class<?> c = Class.forName("org.robovm.rt.GC");
            mHeapSize = c.getMethod("getHeapSize");
            mUnmapped = c.getMethod("getUnmappedBytes");
        } catch (Throwable t) {
            mHeapSize = null;
            mUnmapped = null;
        }
    }

    private static long callMB(Method m) {
        if (m == null) {
            return -1;
        }
        try {
            return ((Number) m.invoke(null)).longValue() / (1024L * 1024L);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Call once per frame; internally throttled. Cheap no-op when disabled. */
    public static void tick() {
        if (!ENABLED) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastLog < 2000) {
            return;
        }
        lastLog = now;
        if (!gcProbed) {
            initGcGetters();
        }
        try {
            Runtime rt = Runtime.getRuntime();
            long heapMB = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
            System.out.println("[MEMLOG] javaHeapMB=" + heapMB
                    + " gcHeapMB=" + callMB(mHeapSize)
                    + " unmappedMB=" + callMB(mUnmapped)
                    + " renderCnt=" + Forge.getContinuousRenderingCount()
                    + " cacheSize=" + Forge.cacheSize);
        } catch (Exception e) {
            // never let the probe crash rendering
        }
    }
}
