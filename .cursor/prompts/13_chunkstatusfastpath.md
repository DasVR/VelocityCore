# Prompt 13 — ChunkStatusFastPath + RegionFileBuffer Detail (S8, S9)

## What to implement

Complete `ChunkStatusFastPath.java` (S8) and `RegionFileBuffer.java` (S9) to their full implementations.
Both systems were introduced in prompts 09 and 12 — this prompt provides the missing implementation details
for the RegionFile I/O warm path and the FastPath pipeline skip logic.

---

## ChunkStatusFastPath.java — Full Implementation

**Package:** `com.velocitycore.system`

**Purpose:** When Minecraft loads a chunk from disk, it runs several pipeline upgrade checks regardless of
whether the chunk is already fully complete. For chunks that Chunky has baked to ChunkStatus.FULL, these
checks are redundant. This system intercepts the loading path and returns the chunk directly when it is
confirmed FULL.

### Core logic

The `tryFastLoad` method (stubbed in prompt 12) must:

1. Return immediately if `requiredStatus != ChunkStatus.FULL` — only fast-path FULL requests.
2. Call `cache.getChunkNow(chunkX, chunkZ)` — if a `LevelChunk` is already in memory, return it.
3. If not in memory, check `PreLoadRing.isChunkFullOnDisk()` (or its cached result) — if FULL, the chunk
   can be promoted directly from NBT without running intermediate pipeline stages.
4. If confirmed FULL on disk but not in memory, return null and let vanilla load it — but record the
   fast-path miss so future loads benefit from the SmartEviction hot-chunk retention.

```java
public static ChunkAccess tryFastLoad(ServerChunkCache cache, int chunkX, int chunkZ,
        ChunkStatus requiredStatus) {
    if (!VCConfig.ENABLE_FAST_PATH.get()) return null;
    if (!ChunkStatus.FULL.equals(requiredStatus)) return null;

    // Fast case: chunk already fully loaded in memory
    LevelChunk existing = cache.getChunkNow(chunkX, chunkZ);
    if (existing != null) {
        // Record access for SmartEviction
        SmartEviction.recordAccess(existing.getPos());
        return existing;
    }

    // Slow fast-path: confirmed FULL on disk → no pipeline stages needed after NBT load
    // Signal to vanilla that no intermediate stages are required by returning null here.
    // The actual skip happens in the mixin via cancellation after vanilla loads from NBT.
    // This method currently only short-circuits in-memory lookups.
    return null;
}
```

### Monitoring

Add a simple counter for debugging:

```java
private static final AtomicLong fastPathHits = new AtomicLong(0);
private static final AtomicLong fastPathMisses = new AtomicLong(0);

/** Returns a status string for /velocitycore status. */
public static String getStatusString() {
    return String.format("FastPath hits=%d misses=%d",
        fastPathHits.get(), fastPathMisses.get());
}
```

---

## RegionFileBuffer.java — Full Implementation

**Package:** `com.velocitycore.system`

**Purpose:** When any chunk from an .mca region file is requested, speculatively reads surrounding sectors
into a heap buffer on a background I/O thread to prime the OS page cache for adjacent reads.

### warmRegionFile — complete

```java
private static final int SECTOR_SIZE = 4096; // .mca file sector size in bytes
private static final int WARM_SECTOR_COUNT = 16; // configurable — default 16 sectors per warm

private static void warmRegionFile(ServerLevel level, ChunkPos triggerChunk) {
    try {
        Path regionPath = getRegionFilePath(level, triggerChunk.x, triggerChunk.z);
        if (!Files.exists(regionPath)) return;

        long fileSize = Files.size(regionPath);
        long warmBytes = (long) WARM_SECTOR_COUNT * SECTOR_SIZE;

        // Read warmBytes from the region file into a discarded buffer
        // This primes the OS page cache — the data itself is not used
        try (FileInputStream fis = new FileInputStream(regionPath.toFile())) {
            byte[] buffer = new byte[(int) Math.min(warmBytes, fileSize)];
            int totalRead = 0;
            while (totalRead < buffer.length) {
                int read = fis.read(buffer, totalRead, buffer.length - totalRead);
                if (read == -1) break;
                totalRead += read;
            }
            // Buffer is immediately discarded — GC collects it
        }
    } catch (IOException e) {
        // IO errors on the warming thread are non-fatal — log at debug level only
        LogManager.getLogger("VelocityCore/RegionFileBuffer")
            .debug("Region file warm failed for {}: {}", triggerChunk, e.getMessage());
    }
}
```

### getRegionFilePath helper

```java
private static Path getRegionFilePath(ServerLevel level, int chunkX, int chunkZ) {
    int regionX = chunkX >> 5;
    int regionZ = chunkZ >> 5;
    // Use LevelStorageSource path utilities
    Path worldDir = level.getServer().getWorldPath(LevelResource.ROOT);
    Path dimensionPath;
    if (level.dimension() == Level.OVERWORLD) {
        dimensionPath = worldDir;
    } else if (level.dimension() == Level.NETHER) {
        dimensionPath = worldDir.resolve("DIM-1");
    } else if (level.dimension() == Level.END) {
        dimensionPath = worldDir.resolve("DIM1");
    } else {
        // Modded dimension — use dimension path from level
        dimensionPath = worldDir.resolve(level.dimension().location().getNamespace())
                                .resolve(level.dimension().location().getPath());
    }
    return dimensionPath.resolve("region").resolve("r." + regionX + "." + regionZ + ".mca");
}
```

### isWarmed / markWarmed (without Caffeine)

If Caffeine is not on the classpath, use a TTL-based ConcurrentHashMap:

```java
private static final ConcurrentHashMap<String, Long> warmedRegionTimes = new ConcurrentHashMap<>();
private static final long WARM_TTL_NS = 30_000_000_000L; // 30 seconds

private static boolean isWarmed(String key) {
    Long t = warmedRegionTimes.get(key);
    return t != null && (System.nanoTime() - t) < WARM_TTL_NS;
}

private static void markWarmed(String key) {
    warmedRegionTimes.put(key, System.nanoTime());
    // Prune old entries (simple: only if map is large)
    if (warmedRegionTimes.size() > 512) {
        long cutoff = System.nanoTime() - WARM_TTL_NS;
        warmedRegionTimes.entrySet().removeIf(e -> e.getValue() < cutoff);
    }
}
```

### Pairing with PreLoadRing

When PreLoadRing issues a chunk load ticket, it should also warm the region file containing that chunk:

```java
// Inside PreLoadRing.issueTicket(), after all checks pass:
RegionFileBuffer.onChunkRead(level, pos);
```

This ensures that when the ticket resolves and the chunk is actually read, the surrounding sectors are already
in the OS page cache.

---

## Constraints

**ChunkStatusFastPath:**
- Must handle the case where `getChunkNow()` returns null gracefully — return null, let vanilla proceed.
- Do not call any disk I/O from `tryFastLoad()` — keep it as a pure in-memory check on the hot path.
- The fast-path counters (`fastPathHits`, `fastPathMisses`) are for diagnostics only — they add negligible cost.

**RegionFileBuffer:**
- `warmRegionFile()` must ONLY read data — never call any method that writes to the world or level state.
- The background thread must be daemon=true to not block server shutdown.
- A region file that does not exist (chunk never generated) returns early without error.
- Do not warm the same region file more than once per 30 seconds to avoid redundant I/O.
- The buffer is allocated, filled from disk, and immediately discarded — no persistent cache of the raw bytes.

## Files to create/edit

- `src/main/java/com/velocitycore/system/ChunkStatusFastPath.java`
- `src/main/java/com/velocitycore/system/RegionFileBuffer.java`
