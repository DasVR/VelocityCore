package com.velocitycore.system;

import com.velocitycore.config.VCConfig;
import net.minecraft.world.level.ChunkPos;

import java.util.concurrent.ConcurrentHashMap;

/**
 * S5 — SpawnRateLimiter
 *
 * Adds a per-chunk cooldown to natural spawning that scales with the server's smoothed TPS.
 * Prevents spawn attempts from running on a chunk that was just processed.
 *
 * Cooldown tiers:
 *   TPS >= 19.0  →  1 tick
 *   TPS >= 17.0  →  2 ticks
 *   TPS >= 15.0  →  4 ticks
 *   TPS  < 15.0  →  8 ticks
 *
 * See .cursor/prompts/04_spawnratelimiter.md for full implementation brief.
 */
public final class SpawnRateLimiter {

    /**
     * Maps ChunkPos.toLong() → last spawn tick for that chunk.
     * ConcurrentHashMap because NaturalSpawner can be called from async chunk loading threads.
     */
    private static final ConcurrentHashMap<Long, Long> lastSpawnTick = new ConcurrentHashMap<>();

    /**
     * Returns true if the given chunk should be skipped for spawning this tick.
     * If VCConfig.ENABLE_SPAWN_LIMITER is false, always returns false.
     *
     * @param chunkPos    the chunk being evaluated
     * @param currentTick the current game tick
     * @return true if the chunk is on cooldown
     */
    public static boolean isCoolingDown(ChunkPos chunkPos, long currentTick) {
        if (!VCConfig.ENABLE_SPAWN_LIMITER.get()) return false;
        Long last = lastSpawnTick.get(chunkPos.toLong());
        if (last == null) return false;
        return (currentTick - last) < cooldownTicks();
    }

    /**
     * Records that a spawn attempt was made for this chunk at the current tick.
     *
     * @param chunkPos    the chunk that was processed
     * @param currentTick the current game tick
     */
    public static void markSpawned(ChunkPos chunkPos, long currentTick) {
        lastSpawnTick.put(chunkPos.toLong(), currentTick);
    }

    /**
     * Removes map entries older than 100 ticks to prevent unbounded growth.
     * Called by ServerTickHandler every 20 ticks.
     *
     * @param currentTick the current game tick
     */
    public static void pruneTick(long currentTick) {
        if (currentTick % 20 != 0) return;
        long cutoff = currentTick - 100;
        lastSpawnTick.entrySet().removeIf(e -> e.getValue() < cutoff);
    }

    public static int getTrackedChunkCount() {
        return lastSpawnTick.size();
    }

    /** Returns the cooldown tick count for the current TPS. */
    private static int cooldownTicks() {
        double tps = ChunkGenThrottle.getSmoothedTps();
        if (tps >= 19.0) return 1;
        if (tps >= 17.0) return 2;
        if (tps >= 15.0) return 4;
        return 8;
    }

    private SpawnRateLimiter() {}
}
