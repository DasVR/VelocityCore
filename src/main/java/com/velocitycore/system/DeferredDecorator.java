package com.velocitycore.system;

import com.velocitycore.config.VCConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.WorldGenLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * S3 — DeferredDecorator
 *
 * Feature decoration (trees, fallen logs, stone piles, WWEE custom vegetation) is deferred from
 * the worldgen thread into a wait-free queue. Tasks are drained on the main server thread during
 * ServerTickEvent Phase.END within ChunkGenThrottle's budget window.
 *
 * All draining happens on the main server thread — block state writes are safe.
 * Queue depth is reported via getQueueDepth() for the /velocitycore status command.
 *
 * See .cursor/prompts/08_deferreddecorator.md for full implementation brief.
 */
public final class DeferredDecorator {

    private static final Logger LOGGER = LogManager.getLogger("VelocityCore/DeferredDecorator");

    /** Wait-free decoration task queue. Enqueue from any thread; drain on main thread only. */
    private static final ConcurrentLinkedQueue<DecorationTask> queue = new ConcurrentLinkedQueue<>();

    /**
     * Represents a single chunk's pending decoration work.
     */
    public static class DecorationTask {
        public final WorldGenLevel level;
        public final ChunkGenerator generator;
        public final ChunkPos chunkPos;
        public final long enqueuedNanos;

        public DecorationTask(WorldGenLevel level, ChunkGenerator generator, ChunkPos chunkPos) {
            this.level = level;
            this.generator = generator;
            this.chunkPos = chunkPos;
            this.enqueuedNanos = System.nanoTime();
        }
    }

    /**
     * Enqueues a decoration task. Called from MixinNoiseBasedChunkGenerator.
     * If the system is disabled, runs decoration immediately inline (vanilla behaviour).
     *
     * @param level     the WorldGenLevel for this chunk
     * @param generator the ChunkGenerator performing decoration
     * @param chunkPos  the chunk position to decorate
     */
    public static void enqueue(WorldGenLevel level, ChunkGenerator generator, ChunkPos chunkPos) {
        if (!VCConfig.ENABLE_DEFERRED_DECORATOR.get()) {
            try {
                generator.applyBiomeDecoration(level, level.getChunk(chunkPos.x, chunkPos.z),
                    level.structureManager());
            } catch (Exception e) {
                LOGGER.warn("Inline decoration failed at {}: {}", chunkPos, e.getMessage());
            }
            return;
        }
        queue.offer(new DecorationTask(level, generator, chunkPos));
    }

    /**
     * Drains pending decoration tasks within ChunkGenThrottle's budget for this tick.
     * Must only be called from the main server thread (ServerTickHandler Phase.END).
     *
     * @param level the ServerLevel being ticked
     */
    public static void drainTick(ServerLevel level) {
        if (!VCConfig.ENABLE_DEFERRED_DECORATOR.get()) return;
        int maxPerTick = VCConfig.DECORATION_PER_TICK.get();
        int drained = 0;

        while (drained < maxPerTick && ChunkGenThrottle.hasBudget()) {
            DecorationTask task = queue.poll();
            if (task == null) break;

            long before = System.nanoTime();
            try {
                task.generator.applyBiomeDecoration(
                    task.level,
                    task.level.getChunk(task.chunkPos.x, task.chunkPos.z),
                    task.level.structureManager()
                );
            } catch (Exception e) {
                LOGGER.warn("Decoration failed at {}: {}", task.chunkPos, e.getMessage());
            }
            long elapsed = System.nanoTime() - before;
            ChunkGenThrottle.charge(elapsed);
            drained++;
        }
    }

    /**
     * Returns the current number of tasks waiting in the decoration queue.
     * Used by /velocitycore status command.
     *
     * @return queue depth
     */
    public static int getQueueDepth() {
        return queue.size();
    }

    private DeferredDecorator() {}
}
