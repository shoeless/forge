package forge.gamemodes.net;

import com.google.common.collect.Maps;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

public class ReplyPool {

    private final Map<Integer, CompletableFuture> pool = Maps.newHashMap();

    public ReplyPool() {
    }

    public void initialize(final int index) {
        synchronized (pool) {
            pool.put(index, new CompletableFuture());
        }
    }

    public void complete(final int index, final Object value) {
        synchronized (pool) {
            final CompletableFuture future = pool.get(index);
            if (future == null) {
                // Entry cleared by cancelAll (disconnect/AFK teardown) before the reply arrived —
                // drop it rather than NPE on the netty thread.
                return;
            }
            future.set(value);
        }
    }

    public Object get(final int index) {
        final CompletableFuture future;
        synchronized (pool) {
            future = pool.get(index);
        }
        if (future == null) {
            // Entry cleared by cancelAll (disconnect teardown) between initialize and get —
            // treat as a cancelled reply rather than NPE.
            return null;
        }
        try {
            return future.get();
        } catch (final InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Cancel all pending replies by completing them with null.
     * This is used when a player is converted to AI to unblock any waiting game threads.
     */
    public void cancelAll() {
        synchronized (pool) {
            for (CompletableFuture future : pool.values()) {
                // Complete with null to unblock waiting threads
                future.set(null);
            }
            pool.clear();
        }
    }

    private static final class CompletableFuture extends FutureTask<Object> {
        public CompletableFuture() {
            super(() -> null);
        }

        @Override
        public void set(final Object v) {
            super.set(v);
        }
    }
}
