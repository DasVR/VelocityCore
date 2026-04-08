package com.velocitycore.system;

import com.velocitycore.config.VCConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * S9 — RegionFileBuffer
 *
 * When any chunk from an .mca region file is requested, speculatively reads the surrounding
 * sector cluster into a heap buffer on a background I/O thread, priming the OS page cache for
 * adjacent chunk reads. The buffer is immediately discarded — no data is cached in memory.
 *
 * Background thread is daemon=true so it does not prevent JVM shutdown.
 * Never writes to the world. Read-only warming only.
 *
 * See .cursor/prompts/09_preloadring.md and 13_chunkstatusfastpath.md for implementation brief.
 */
public final class RegionFileBuffer {

    private static final Logger LOGGER = LogManager.getLogger("VelocityCore/RegionFileBuffer");

    private static final int SECTOR_SIZE = 4096;
    private static final int WARM_SECTOR_COUNT = 16;
    private static final long WARM_TTL_NS = 30_000_000_000L; // 30 seconds

    /** Background I/O thread — daemon, never writes world data. */
    private static final ExecutorService ioThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VelocityCore-RegionIO");
        t.setDaemon(true);
        return t;
    });

    /** Tracks when each region file was last warmed. Key = "regionX,regionZ". */
    private static final ConcurrentHashMap<String, Long> warmedRegionTimes = new ConcurrentHashMap<>();

    /**
     * Called when a chunk is first requested from an .mca region file.
     * Submits a background task to warm the surrounding sector cluster if not recently warmed.
     * If VCConfig.ENABLE_REGION_BUFFER is false, does nothing.
     *
     * @param level    the ServerLevel containing the region file
     * @param chunkPos the chunk being read
     */
    public static void onChunkRead(ServerLevel level, ChunkPos chunkPos) {
        if (!VCConfig.ENABLE_REGION_BUFFER.get()) return;
        String key = regionKey(chunkPos);
        if (isWarmed(key)) return;
        markWarmed(key);
        ioThread.submit(() -> warmRegionFile(level, chunkPos));
    }

    public static int getWarmRegionCount() {
        return warmedRegionTimes.size();
    }

    /**
     * Background task: reads up to WARM_SECTOR_COUNT sectors from the region file to prime
     * the OS page cache. The read buffer is immediately discarded.
     *
     * This method ONLY reads data — it never writes to the world.
     *
     * @param level    the ServerLevel
     * @param chunkPos the triggering chunk position
     */
    private static void warmRegionFile(ServerLevel level, ChunkPos chunkPos) {
        try {
            Path regionPath = getRegionFilePath(level, chunkPos.x, chunkPos.z);
            if (!Files.exists(regionPath)) return;

            long fileSize = Files.size(regionPath);
            long warmBytes = (long) WARM_SECTOR_COUNT * SECTOR_SIZE;

            try (FileInputStream fis = new FileInputStream(regionPath.toFile())) {
                byte[] buffer = new byte[(int) Math.min(warmBytes, fileSize)];
                int totalRead = 0;
                while (totalRead < buffer.length) {
                    int read = fis.read(buffer, totalRead, buffer.length - totalRead);
                    if (read == -1) break;
                    totalRead += read;
                }
                // Buffer discarded — GC collects it; only the OS page cache benefits
            }
        } catch (IOException e) {
            LOGGER.debug("Region file warm failed for {}: {}", chunkPos, e.getMessage());
        }
    }

    /**
     * Constructs the filesystem path for the .mca region file containing the given chunk.
     *
     * @param level  the ServerLevel
     * @param chunkX chunk X
     * @param chunkZ chunk Z
     * @return path to the .mca file
     */
    private static Path getRegionFilePath(ServerLevel level, int chunkX, int chunkZ) {
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        Path worldDir = level.getServer().getWorldPath(LevelResource.ROOT);
        Path dimensionPath;
        if (level.dimension() == Level.OVERWORLD) {
            dimensionPath = worldDir;
        } else if (level.dimension() == Level.NETHER) {
            dimensionPath = worldDir.resolve("DIM-1");
        } else if (level.dimension() == Level.END) {
            dimensionPath = worldDir.resolve("DIM1");
        } else {
            dimensionPath = worldDir
                .resolve(level.dimension().location().getNamespace())
                .resolve(level.dimension().location().getPath());
        }
        return dimensionPath.resolve("region").resolve("r." + regionX + "." + regionZ + ".mca");
    }

    private static String regionKey(ChunkPos pos) {
        return (pos.x >> 5) + "," + (pos.z >> 5);
    }

    private static boolean isWarmed(String key) {
        Long t = warmedRegionTimes.get(key);
        return t != null && (System.nanoTime() - t) < WARM_TTL_NS;
    }

    private static void markWarmed(String key) {
        warmedRegionTimes.put(key, System.nanoTime());
        if (warmedRegionTimes.size() > 512) {
            long cutoff = System.nanoTime() - WARM_TTL_NS;
            warmedRegionTimes.entrySet().removeIf(e -> e.getValue() < cutoff);
        }
    }

    private RegionFileBuffer() {}
}
