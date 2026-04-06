package com.velocitycore.system;

import com.velocitycore.config.VCConfig;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.concurrent.atomic.AtomicLong;

/**
 * S8 — ChunkStatusFastPath
 *
 * When Minecraft loads a chunk from disk, it runs several pipeline upgrade checks regardless of
 * whether the chunk is already fully complete. For ChunkStatus.FULL chunks that are already in
 * memory, this system returns them directly, skipping all pipeline checks.
 *
 * Only activates when VCConfig.ENABLE_FAST_PATH is true and requiredStatus == FULL.
 * Falls through to vanilla pipeline for all other cases.
 *
 * See .cursor/prompts/13_chunkstatusfastpath.md and 12_smarteviction.md for implementation brief.
 */
public final class ChunkStatusFastPath {

    private static final AtomicLong fastPathHits = new AtomicLong(0);
    private static final AtomicLong fastPathMisses = new AtomicLong(0);

    /**
     * Attempts to return a fully-loaded LevelChunk directly if it is already in memory.
     * Returns null if the fast path does not apply (fall through to vanilla pipeline).
     *
     * @param cache          the ServerChunkCache
     * @param chunkX         chunk X
     * @param chunkZ         chunk Z
     * @param requiredStatus the status being requested
     * @return a LevelChunk if fast-path applies, null otherwise
     */
    public static ChunkAccess tryFastLoad(ServerChunkCache cache, int chunkX, int chunkZ,
            ChunkStatus requiredStatus) {
        if (!VCConfig.ENABLE_FAST_PATH.get()) return null;
        if (!ChunkStatus.FULL.equals(requiredStatus)) return null;

        LevelChunk existing = cache.getChunkNow(chunkX, chunkZ);
        if (existing != null) {
            SmartEviction.recordAccess(existing.getPos());
            fastPathHits.incrementAndGet();
            return existing;
        }

        fastPathMisses.incrementAndGet();
        return null;
    }

    /**
     * Returns a status string for /velocitycore status.
     *
     * @return formatted hit/miss string
     */
    public static String getStatusString() {
        return String.format("FastPath hits=%d misses=%d", fastPathHits.get(), fastPathMisses.get());
    }

    private ChunkStatusFastPath() {}
}
