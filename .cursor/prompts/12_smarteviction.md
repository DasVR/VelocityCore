# Prompt 12 — SmartEviction + MixinServerChunkCache (S7)

## What to implement

Complete the full implementation of `SmartEviction.java` (S7) and all injection points in
`MixinServerChunkCache.java`. SmartEviction was introduced in prompt 09 — this prompt provides the full
MixinServerChunkCache detail including the eviction override and access recording injections.

---

## SmartEviction.java — Full Implementation

See prompt 09 for fields and API. This prompt adds the full `pruneIfDue()` implementation and LFU ceiling
enforcement.

### pruneIfDue — complete

```java
public static void pruneIfDue(long gameTick) {
    if (!VCConfig.ENABLE_SMART_EVICTION.get()) return;

    // Decay: halve all counters every 12,000 ticks (10 minutes)
    if (gameTick - lastDecayTick >= DECAY_INTERVAL_TICKS) {
        lastDecayTick = gameTick;
        accessCounts.forEach((k, v) -> v.set(v.get() / 2));
    }

    // Prune every 1,200 ticks (60 seconds)
    if (gameTick % PRUNE_INTERVAL_TICKS != 0) return;

    // Remove zero-count entries
    accessCounts.entrySet().removeIf(e -> e.getValue().get() == 0);

    // Enforce HOT_CHUNK_MAX ceiling: if hot set exceeds limit, evict coldest entries
    int maxHot = VCConfig.HOT_CHUNK_MAX.get();
    List<Map.Entry<Long, AtomicInteger>> hotEntries = accessCounts.entrySet().stream()
        .filter(e -> e.getValue().get() > HOT_THRESHOLD)
        .sorted(Comparator.comparingInt(e -> e.getValue().get()))
        .collect(Collectors.toList());

    if (hotEntries.size() > maxHot) {
        int toEvict = hotEntries.size() - maxHot;
        for (int i = 0; i < toEvict; i++) {
            // Reset to below threshold so isHot() returns false
            hotEntries.get(i).getValue().set(HOT_THRESHOLD / 2);
        }
    }
}
```

---

## MixinServerChunkCache.java

**Package:** `com.velocitycore.mixin`

**Target class:** `net.minecraft.server.level.ServerChunkCache`

**Purpose:** Three injections:
1. Record chunk accesses for SmartEviction
2. Override eviction decisions for hot chunks
3. Expose `storageFolder` for PreLoadRing's disk status check (via @Accessor)

### @Mixin declaration

```java
@Mixin(ServerChunkCache.class)
public abstract class MixinServerChunkCache { ... }
```

### Injection 1 — Record chunk access

```java
// Injection point rationale: getChunkNow() is called by entity ticking systems to retrieve
// loaded chunks. Injecting at RETURN captures every successful chunk lookup, providing an
// accurate access frequency signal for SmartEviction without touching the chunk loading pipeline.
@Inject(
    method = "getChunkNow",
    at = @At("RETURN")
)
private void vc_recordAccess(int chunkX, int chunkZ, CallbackInfoReturnable<LevelChunk> cir) {
    if (!VCConfig.ENABLE_SMART_EVICTION.get()) return;
    if (cir.getReturnValue() != null) {
        SmartEviction.recordAccess(new ChunkPos(chunkX, chunkZ));
    }
}
```

### Injection 2 — Fast path for FULL chunks

```java
// Injection point rationale: getChunk() with the full status check is the entry point for
// chunk pipeline promotions. Injecting at HEAD allows ChunkStatusFastPath to skip pipeline
// stages for confirmed FULL chunks, reducing overhead for every chunk load in a Chunky world.
@Inject(
    method = "getChunk(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
    at = @At("HEAD"),
    cancellable = true
)
private void vc_fastPathCheck(int chunkX, int chunkZ, ChunkStatus requiredStatus, boolean load,
        CallbackInfoReturnable<ChunkAccess> cir) {
    if (!VCConfig.ENABLE_FAST_PATH.get()) return;
    ChunkAccess fast = ChunkStatusFastPath.tryFastLoad(
        (ServerChunkCache)(Object)this, chunkX, chunkZ, requiredStatus);
    if (fast != null) {
        cir.setReturnValue(fast);
    }
}
```

### @Accessor — storageFolder

```java
// Exposes ChunkMap.storageFolder (a File) to PreLoadRing without reflection.
// The actual field name in Mojmap 1.20.1 is "storageFolder" on ChunkMap.
// Add this @Mixin on ChunkMap instead of ServerChunkCache if the field lives there.
@Accessor("storageFolder")
public abstract File getStorageFolder();
```

If `storageFolder` is on `ChunkMap` (which it is in 1.20.1), create a separate
`MixinChunkMap.java` with this accessor instead.

### Injection 3 — RegionFile warm trigger

```java
// Injection point rationale: getChunk() returning from a disk-load path is the signal that
// a region file was accessed. We fire RegionFileBuffer.onChunkRead() to schedule background
// warming of adjacent sectors. RETURN is used so vanilla loading completes first.
@Inject(
    method = "getChunk(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
    at = @At("RETURN")
)
private void vc_regionWarm(int chunkX, int chunkZ, ChunkStatus requiredStatus, boolean load,
        CallbackInfoReturnable<ChunkAccess> cir) {
    if (!VCConfig.ENABLE_REGION_BUFFER.get()) return;
    ServerLevel level = ((ServerChunkCache)(Object)this).getLevel();
    RegionFileBuffer.onChunkRead(level, new ChunkPos(chunkX, chunkZ));
}
```

---

## ChunkStatusFastPath.java — called from MixinServerChunkCache

**Package:** `com.velocitycore.system`

```java
/**
 * Attempts to return a fully-loaded LevelChunk directly if the chunk is confirmed FULL on disk
 * and is already present in the chunk cache. Skips intermediate pipeline stages.
 *
 * Returns null if:
 *   - VCConfig.ENABLE_FAST_PATH is false
 *   - The chunk is not already loaded in memory
 *   - The requested status is not FULL
 *   - The chunk's on-disk status cannot be confirmed
 *
 * @param cache         the ServerChunkCache
 * @param chunkX        chunk X
 * @param chunkZ        chunk Z
 * @param requiredStatus the status being requested
 * @return a LevelChunk if fast-path applies, null otherwise
 */
public static ChunkAccess tryFastLoad(ServerChunkCache cache, int chunkX, int chunkZ,
        ChunkStatus requiredStatus) {
    if (!ChunkStatus.FULL.equals(requiredStatus)) return null;
    // Check if already in the ChunkCache (DistanceManager / visible chunks map)
    // Use cache.getChunkNow(chunkX, chunkZ) — if non-null and isFullChunk, return directly.
    LevelChunk existing = cache.getChunkNow(chunkX, chunkZ);
    if (existing != null) return existing; // already full, skip pipeline
    return null; // fall through to vanilla pipeline
}
```

---

## Constraints

- `getChunkNow` injection at RETURN must null-check the return value before calling `recordAccess`.
- The fast-path injection must return null (not cancel) when conditions are not met — falling through to vanilla.
- Do not inject into `getChunk` with a primitive `boolean` — use the full descriptor to target the correct overload.
- `RegionFileBuffer.onChunkRead` must not be called on every getChunk call — only on cache-miss loads.
  Consider checking `cir.getReturnValue() != null` and comparing to a "was already loaded" flag.

## Files to create/edit

- `src/main/java/com/velocitycore/system/SmartEviction.java`
- `src/main/java/com/velocitycore/system/ChunkStatusFastPath.java`
- `src/main/java/com/velocitycore/mixin/MixinServerChunkCache.java`
