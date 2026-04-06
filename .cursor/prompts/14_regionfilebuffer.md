# Prompt 14 — RegionFileBuffer Integration + MixinServerChunkCache Final (S9)

## What to implement

This prompt finalises `MixinServerChunkCache.java` with the complete set of injections for all five server
systems that use it (S7 SmartEviction, S8 ChunkStatusFastPath, S9 RegionFileBuffer). It also verifies that all
mixin targets are correctly specified for Forge 1.20.1-47.3.0.

---

## MixinServerChunkCache.java — Final Complete Version

**Package:** `com.velocitycore.mixin`
**Target class:** `net.minecraft.server.level.ServerChunkCache`

All injections in one file:

```java
@Mixin(ServerChunkCache.class)
public abstract class MixinServerChunkCache {

    // -------------------------------------------------------------------------
    // Injection 1: Fast path for FULL chunks (S8)
    // -------------------------------------------------------------------------
    // Rationale: HEAD of getChunk(int, int, ChunkStatus, boolean) is the earliest point to
    // intercept a chunk retrieval. For FULL chunks already in memory, we skip all pipeline
    // checks and return immediately. This is safe because getChunkNow() is a pure map lookup.
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
        if (fast != null) cir.setReturnValue(fast);
    }

    // -------------------------------------------------------------------------
    // Injection 2: Access recording for SmartEviction (S7)
    // -------------------------------------------------------------------------
    // Rationale: RETURN on getChunkNow() captures every successful in-memory chunk lookup.
    // This is the access signal for LFU tracking — getChunkNow() is called by entity ticking.
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

    // -------------------------------------------------------------------------
    // Injection 3: Region file warming for RegionFileBuffer (S9)
    // -------------------------------------------------------------------------
    // Rationale: RETURN on getChunk() with load=true fires after a chunk has been loaded from
    // disk. At this point we know which region file was accessed, making it the right trigger for
    // speculative warming of adjacent sectors on the background I/O thread.
    @Inject(
        method = "getChunk(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At("RETURN")
    )
    private void vc_regionWarm(int chunkX, int chunkZ, ChunkStatus requiredStatus, boolean load,
            CallbackInfoReturnable<ChunkAccess> cir) {
        if (!VCConfig.ENABLE_REGION_BUFFER.get()) return;
        if (!load || cir.getReturnValue() == null) return;
        ServerLevel level = ((ServerChunkCache)(Object)this).getLevel();
        RegionFileBuffer.onChunkRead(level, new ChunkPos(chunkX, chunkZ));
    }
}
```

### Optional @Accessor on ChunkMap

If `PreLoadRing.isChunkFullOnDisk()` needs the storage folder path from `ChunkMap`, create a separate mixin:

```java
// File: src/main/java/com/velocitycore/mixin/MixinChunkMap.java
@Mixin(ChunkMap.class)
public abstract class MixinChunkMap {

    // Exposes the storageFolder field for PreLoadRing's disk status check.
    // This avoids reflection and is refmap-safe across obfuscation mappings.
    @Accessor("storageFolder")
    public abstract File vc_getStorageFolder();
}
```

If `MixinChunkMap` is added, register it in `velocitycore.mixins.json` under the `mixins` array.

---

## Verification checklist

After all injections are complete, verify each in a test world:

### S7 SmartEviction verification
1. Explore a base area for several minutes to build access counts.
2. Run `/velocitycore status` — confirm "hot chunks: N" is non-zero.
3. Move 500 blocks away. Confirm hot chunk count does not drop to zero (they should persist).
4. Wait 15 minutes. Confirm hot chunk count eventually decays.

### S8 ChunkStatusFastPath verification
1. Load a Chunky pre-generated world.
2. Enable fast path in config.
3. Run `/velocitycore status` — confirm "FastPath hits" increments as you move.
4. Disable fast path in config, reload, confirm "hits" stays at 0.

### S9 RegionFileBuffer verification
1. Teleport to a new, Chunky-generated area.
2. Use a disk I/O monitor (iostat on Linux) and observe sequential read bursts on the .mca region files.
3. Subsequent adjacent chunk loads should be faster (OS page cache warm).

---

## Mixin descriptor reference (Forge 1.20.1 Mojmap)

| Method | Descriptor |
|---|---|
| ServerChunkCache.getChunk | `(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;` |
| ServerChunkCache.getChunkNow | `(II)Lnet/minecraft/server/level/ServerChunkCache$MainThreadExecutor;` — verify this, it may differ |
| ChunkMap.storageFolder | `Ljava/io/File;` field |

**IMPORTANT:** Always verify method descriptors against the actual decompiled Forge 1.20.1 sources before
writing `@Inject` annotations. Incorrect descriptors cause mixin apply failures at startup.

---

## Constraints

- Multiple `@Inject` annotations targeting the same method (`getChunk`) must use distinct method names
  (`vc_fastPathCheck` and `vc_regionWarm`) so they are each applied independently.
- The `getChunkNow` descriptor must be verified — its return type and parameter list may differ in 1.20.1
  from what is shown here. Check the actual class in the Forge 1.20.1 decompilation.
- `MixinChunkMap` (if added) must be listed in `velocitycore.mixins.json` alongside the other 5 mixins.

## Files to create/edit

- `src/main/java/com/velocitycore/mixin/MixinServerChunkCache.java` (finalize)
- `src/main/java/com/velocitycore/mixin/MixinChunkMap.java` (optional, if storageFolder access is needed)
- `src/main/resources/velocitycore.mixins.json` (add MixinChunkMap entry if created)
