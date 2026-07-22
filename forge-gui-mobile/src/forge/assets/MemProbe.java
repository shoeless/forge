package forge.assets;

import forge.Forge;

import java.lang.reflect.Method;

/**
 * Gated per-frame memory probe (memory-reduction work). Throttled to ~2s. Logs Java heap + the downloaded-card-
 * texture native footprint + cache sizes + (on iOS) the bdwgc native heap/unmapped bytes + the continuous-render
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
    private static Method mHeapSize, mUnmapped, mCount, mDivisor;

    private MemProbe() { }

    private static void initGcGetters() {
        gcProbed = true;
        try {
            Class<?> c = Class.forName("org.robovm.rt.GC");
            mHeapSize = c.getMethod("getHeapSize");
            mUnmapped = c.getMethod("getUnmappedBytes");
            mCount = c.getMethod("getCount");
            mDivisor = c.getMethod("getFreeSpaceDivisor");
        } catch (Throwable t) {
            mHeapSize = null;
            mUnmapped = null;
            mCount = null;
            mDivisor = null;
        }
    }

    private static long callRaw(Method m) {
        if (m == null) {
            return -1;
        }
        try {
            return ((Number) m.invoke(null)).longValue();
        } catch (Throwable t) {
            return -1;
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
            ImageCache ic = ImageCache.getInstance();
            // native footprint of downloaded card images ~= resident Pixmap bytes + an equal GPU texture
            long dlNativeMB = (ic.getDownloadedPixmapBytes() * 2L) / (1024L * 1024L);
            System.out.println("[MEMLOG] javaHeapMB=" + heapMB
                    + " gcHeapMB=" + callMB(mHeapSize)
                    + " unmappedMB=" + callMB(mUnmapped)
                    + " gcCount=" + callRaw(mCount)
                    + " gcDivisor=" + callRaw(mDivisor)
                    + " dlTex=" + ic.getDownloadedTextureCount() + "/" + ImageCache.getDownloadedTextureCacheMax()
                    + " dlNativeMB=" + dlNativeMB
                    + " cardsLoaded=" + ic.getCardsLoadedCount()
                    + " renderCnt=" + Forge.getContinuousRenderingCount()
                    + " cacheSize=" + Forge.cacheSize);
        } catch (Exception e) {
            // never let the probe crash rendering
        }
    }
}
