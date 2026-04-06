# Prompt 11 — PreLoadRing Detail + Chunky Compatibility Contract (S6)

## What to implement

This prompt supplements prompt 09 with the full Chunky compatibility contract for `PreLoadRing.java`. The
class structure was specified in prompt 09. This prompt details the `isChunkFullOnDisk` implementation,
the world border detection, and the velocity blending algorithm.

---

## isChunkFullOnDisk — Full Implementation

The most critical safety check in PreLoadRing. Must never return true for a chunk that is not fully baked,
because issuing a load ticket on a non-FULL chunk triggers the chunk pipeline and causes gen work — exactly
what VelocityCore is supposed to defer.

### Implementation path (1.20.1 Forge)

```java
private static final Map<Long, Boolean> diskStatusCache = new ConcurrentHashMap<>();
private static final Map<Long, Long> diskStatusCacheTime = new ConcurrentHashMap<>();
private static final long DISK_CACHE_TTL_NS = 60_000_000_000L; // 60 seconds

private static boolean isChunkFullOnDisk(ServerLevel level, int chunkX, int chunkZ) {
    long key = ChunkPos.asLong(chunkX, chunkZ);
    Long cachedTime = diskStatusCacheTime.get(key);
    if (cachedTime != null && (System.nanoTime() - cachedTime) < DISK_CACHE_TTL_NS) {
        Boolean cached = diskStatusCache.get(key);
        if (cached != null) return cached;
    }

    boolean result = false;
    try {
        // Access the ChunkMap to get the RegionFileStorage path
        ChunkMap chunkMap = level.getChunkSource().chunkMap;
        // ChunkMap.getChunkPath(ChunkPos) or similar — check Forge 1.20.1 access
        // Alternative: construct the path manually from level.getChunkSource().chunkMap.storageFolder
        Path regionPath = getRegionFilePath(level, chunkX, chunkZ);
        if (!Files.exists(regionPath)) {
            result = false;
        } else {
            // Read the chunk NBT from the .mca file
            // Use net.minecraft.world.level.chunk.storage.RegionFile or
            // net.minecraft.world.level.chunk.storage.RegionFileStorage
            CompoundTag chunkNbt = readChunkNbt(regionPath, chunkX, chunkZ);
            if (chunkNbt != null) {
                String status = chunkNbt.getString("Status");
                // In 1.20.1 the Status tag is at the root of the chunk NBT
                // Older formats may have it under "Level" → fall back if empty
                if (status.isEmpty() && chunkNbt.contains("Level")) {
                    status = chunkNbt.getCompound("Level").getString("Status");
                }
                result = "minecraft:full".equals(status) || "full".equals(status);
            }
        }
    } catch (Exception e) {
        // IO errors = conservatively return false
        result = false;
    }

    diskStatusCache.put(key, result);
    diskStatusCacheTime.put(key, System.nanoTime());
    return result;
}
```

### Path construction helper

```java
private static Path getRegionFilePath(ServerLevel level, int chunkX, int chunkZ) {
    // Region file coordinates: chunk >> 5
    int regionX = chunkX >> 5;
    int regionZ = chunkZ >> 5;
    // Forge 1.20.1: level.getChunkSource().chunkMap has a storageFolder field
    // Access it via @Accessor mixin or reflection
    Path storageFolder = level.getChunkSource().chunkMap.storageFolder.toPath();
    return storageFolder.resolve("region").resolve("r." + regionX + "." + regionZ + ".mca");
}
```

If `chunkMap.storageFolder` is not directly accessible, add a `@Accessor` to `MixinServerChunkCache` to
expose it, or use Forge's `DimensionType.getStorageFolder()` path construction.

---

## World Border Detection

```java
/**
 * Checks whether a chunk position is inside the server's world border.
 * Reads the border bounds at runtime — never hardcodes a radius.
 *
 * @param level    the ServerLevel whose border to check
 * @param chunkPos the chunk position to test
 * @return true if the chunk center is within the world border
 */
private static boolean isInsideBorder(ServerLevel level, ChunkPos chunkPos) {
    WorldBorder border = level.getWorldBorder();
    // WorldBorder.isWithinBounds(ChunkPos) checks if the chunk AABB intersects the border
    return border.isWithinBounds(chunkPos);
}
```

---

## Velocity Blending Algorithm

The server independently computes velocity from position deltas. When a client hint arrives, it is blended:

```
serverVx = (currentPos.x - history[(head - 5 + 5) % 5].x) / 5.0f
serverVz = (currentPos.z - history[(head - 5 + 5) % 5].z) / 5.0f

// When client hint received (alpha = 0.7 favouring client):
state.vx = 0.7f * hintVx + 0.3f * serverVx
state.vz = 0.7f * hintVz + 0.3f * serverVz
```

If no hint has been received in the last 10 ticks, use server estimate only. This handles:
- Players without the client companion: pure server estimate
- Players with companion: blended for elytra/horse/boat precision

---

## Ticket ring calculation

Given the player's velocity vector (vx, vz) and read-ahead count N:

```java
private static List<ChunkPos> computeRing(ChunkPos playerChunk, float vx, float vz, int readAhead) {
    List<ChunkPos> ring = new ArrayList<>();
    // Normalize direction
    double len = Math.sqrt(vx * vx + vz * vz);
    if (len < 0.01) return ring; // not moving
    double dx = vx / len;
    double dz = vz / len;

    for (int i = 1; i <= readAhead; i++) {
        int cx = playerChunk.x + (int) Math.round(dx * i);
        int cz = playerChunk.z + (int) Math.round(dz * i);
        ring.add(new ChunkPos(cx, cz));
    }
    return ring;
}
```

---

## Constraints

- `isChunkFullOnDisk` must be called on the background I/O thread or accept the disk read latency.
  Consider submitting the check to `RegionFileBuffer.ioThread` and caching the result.
- The 60-second TTL cache prevents hammering the disk on every tick for the same chunk coordinates.
- If `storageFolder` requires reflection or an accessor, prefer the `@Accessor` mixin approach.
- Never call `Files.exists()` or any IO on the main server thread inside the tick loop.
  Gate the entire PreLoadRing.tick() disk check behind a pre-computed cache lookup first.

## Files to create/edit

- `src/main/java/com/velocitycore/system/PreLoadRing.java` (full implementation from prompt 09 + this prompt)
- `src/main/java/com/velocitycore/mixin/MixinServerChunkCache.java` (add storageFolder @Accessor if needed)
