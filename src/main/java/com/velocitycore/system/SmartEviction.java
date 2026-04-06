package com.velocitycore.system;

import com.velocitycore.config.VCConfig;
import net.minecraft.world.level.ChunkPos;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * S7 — SmartEviction
 *
 * Tracks chunk access frequency with a counter per ChunkPos. Hot chunks (>20 accesses in the
 * last 10 minutes) are kept alive in memory even without a nearby player, preventing base-area
 * eviction and the resulting disk re-reads on player return.
 *
 * Uses LFU (Least Frequently Used) eviction when the hot-chunk ceiling is hit.
 * Counter decay: halved every 10 minutes (12,000 ticks) to prevent permanent retention.
 * Prune runs every 60 seconds (1,200 ticks).
 *
 * See .cursor/prompts/09_preloadring.md and 12_smarteviction.md for full implementation brief.
 */
public final class SmartEviction {

    private static final ConcurrentHashMap<Long, AtomicInteger> accessCounts = new ConcurrentHashMap<>();

    private static long lastDecayTick = 0L;
    private static final long DECAY_INTERVAL_TICKS = 12_000L;
    private static final long PRUNE_INTERVAL_TICKS = 1_200L;
    private static final int HOT_THRESHOLD = 20;

    /**
     * Records an access to a chunk. Increments its counter.
     * Called from MixinServerChunkCache when a chunk is accessed for entity ticking.
     *
     * @param chunkPos the accessed chunk position
     */
    public static void recordAccess(ChunkPos chunkPos) {
        if (!VCConfig.ENABLE_SMART_EVICTION.get()) return;
        accessCounts.computeIfAbsent(chunkPos.toLong(), k -> new AtomicInteger(0)).incrementAndGet();
    }

    /**
     * Returns true if the given chunk should be kept hot in memory.
     *
     * @param chunkPos the chunk to check
     * @return true if the chunk has a hot access count
     */
    public static boolean isHot(ChunkPos chunkPos) {
        if (!VCConfig.ENABLE_SMART_EVICTION.get()) return false;
        AtomicInteger counter = accessCounts.get(chunkPos.toLong());
        return counter != null && counter.get() > HOT_THRESHOLD;
    }

    /**
     * Prunes and decays the access counter map. Called by ServerTickHandler.
     * Decay: halve all counters every DECAY_INTERVAL_TICKS.
     * Prune: remove zero-count entries and enforce HOT_CHUNK_MAX every PRUNE_INTERVAL_TICKS.
     *
     * @param gameTick current game tick
     */
    public static void pruneIfDue(long gameTick) {
        if (!VCConfig.ENABLE_SMART_EVICTION.get()) return;

        if (gameTick - lastDecayTick >= DECAY_INTERVAL_TICKS) {
            lastDecayTick = gameTick;
            accessCounts.forEach((k, v) -> v.set(v.get() / 2));
        }

        if (gameTick % PRUNE_INTERVAL_TICKS != 0) return;

        accessCounts.entrySet().removeIf(e -> e.getValue().get() == 0);

        int maxHot = VCConfig.HOT_CHUNK_MAX.get();
        List<Map.Entry<Long, AtomicInteger>> hotEntries = accessCounts.entrySet().stream()
            .filter(e -> e.getValue().get() > HOT_THRESHOLD)
            .sorted(Comparator.comparingInt(e -> e.getValue().get()))
            .collect(Collectors.toList());

        if (hotEntries.size() > maxHot) {
            int toEvict = hotEntries.size() - maxHot;
            for (int i = 0; i < toEvict; i++) {
                hotEntries.get(i).getValue().set(HOT_THRESHOLD / 2);
            }
        }
    }

    /**
     * Returns the count of chunks currently in the hot set.
     * Used by /velocitycore status.
     *
     * @return hot chunk count
     */
    public static int getHotChunkCount() {
        return (int) accessCounts.values().stream().filter(c -> c.get() > HOT_THRESHOLD).count();
    }

    public static int getTrackedChunkCount() {
        return accessCounts.size();
    }

    public static long getLastDecayTick() {
        return lastDecayTick;
    }

    private SmartEviction() {}
}
