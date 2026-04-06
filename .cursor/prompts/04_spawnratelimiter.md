# Prompt 04 — SpawnRateLimiter + MixinNaturalSpawner (S5)

## What to implement

Implement `SpawnRateLimiter.java` (S5) and its injection point in `MixinNaturalSpawner.java`. This is the
fastest visible server-side win — measurable with spark immediately after deployment.

---

## SpawnRateLimiter.java

**Package:** `com.velocitycore.system`

**Purpose:** Adds a per-chunk cooldown to natural spawning that scales with the server's smoothed TPS.
Vanilla attempts to spawn mobs in every eligible chunk every single tick. With WWEE's dense biome layout and
large modded mob pools, the spawner iterates an enormous list per chunk per tick. This system prevents that by
skipping spawn attempts on a chunk that was just processed.

### Cooldown tiers

```
TPS >= 19.0  →  1 tick cooldown
TPS >= 17.0  →  2 tick cooldown
TPS >= 15.0  →  4 tick cooldown
TPS  < 15.0  →  8 tick cooldown
```

### Fields

```java
/**
 * Maps ChunkPos.asLong() → last spawn tick for that chunk.
 * ConcurrentHashMap because the NaturalSpawner can be called from async chunk loading threads.
 */
private static final ConcurrentHashMap<Long, Long> lastSpawnTick = new ConcurrentHashMap<>();
```

### Public API

```java
/**
 * Returns true if the given chunk should be skipped for spawning this tick.
 * Called from MixinNaturalSpawner at the HEAD of the spawn method.
 *
 * If VCConfig.ENABLE_SPAWN_LIMITER is false, always returns false (never skip).
 *
 * @param chunkPos the chunk being evaluated for spawning
 * @param currentTick the current game tick from ServerTickHandler
 * @return true if the chunk is on cooldown and spawning should be skipped
 */
public static boolean isCoolingDown(ChunkPos chunkPos, long currentTick) {
    if (!VCConfig.ENABLE_SPAWN_LIMITER.get()) return false;
    long key = chunkPos.toLong();
    Long last = lastSpawnTick.get(key);
    if (last == null) return false;
    return (currentTick - last) < cooldownTicks();
}

/**
 * Records that a spawn attempt was made for this chunk at the current tick.
 * Called from MixinNaturalSpawner after a spawn attempt completes (not cancelled).
 *
 * @param chunkPos the chunk that was processed
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

/** Returns the cooldown tick count for the current TPS. */
private static int cooldownTicks() {
    double tps = ChunkGenThrottle.getSmoothedTps();
    if (tps >= 19.0) return 1;
    if (tps >= 17.0) return 2;
    if (tps >= 15.0) return 4;
    return 8;
}
```

---

## MixinNaturalSpawner.java

**Package:** `com.velocitycore.mixin`

**Target class:** `net.minecraft.world.level.NaturalSpawner`

**Purpose:** Intercepts chunk spawn processing to apply the SpawnRateLimiter cooldown check and also
supports MobCapGuard (S11) per-category soft cap enforcement.

### Injection — spawnForChunk cooldown

```java
// Injection point rationale: @At("HEAD") on the method that processes spawning for a single chunk
// allows us to short-circuit the entire spawn iteration for that chunk without touching any
// vanilla spawn logic. This is safer than injecting mid-method.
@Inject(
    method = "spawnForChunk",
    at = @At("HEAD"),
    cancellable = true
)
private static void vc_spawnRateLimitCheck(
        ServerChunkCache cache,
        ServerLevel level,
        LevelChunk chunk,
        NaturalSpawner.SpawnState spawnState,
        boolean spawnFriendlies,
        boolean spawnEnemies,
        boolean newChunk,
        CallbackInfo ci) {

    if (!VCConfig.ENABLE_SPAWN_LIMITER.get()) return;
    long tick = EntityActivationManager.getGameTick();
    if (SpawnRateLimiter.isCoolingDown(chunk.getPos(), tick)) {
        ci.cancel();
        return;
    }
    SpawnRateLimiter.markSpawned(chunk.getPos(), tick);
}
```

### Injection — MobCapGuard category check

```java
// Injection point rationale: @At("HEAD") on spawnCategoryForChunk allows MobCapGuard to cancel
// the entire category spawn pass for a given MobCategory before any iteration occurs.
// This is the earliest cancellation point that is category-aware.
@Inject(
    method = "spawnCategoryForChunk",
    at = @At("HEAD"),
    cancellable = true
)
private static void vc_mobCapGuardCheck(
        MobCategory category,
        ServerLevel level,
        LevelChunk chunk,
        NaturalSpawner.SpawnPredicate predicate,
        NaturalSpawner.AfterSpawnCallback callback,
        CallbackInfo ci) {

    if (!VCConfig.ENABLE_MOB_CAP_GUARD.get()) return;
    if (MobCapGuard.isCategoryOver(category, level)) {
        ci.cancel();
    }
}
```

### Access needed

`EntityActivationManager.getGameTick()` — this is a simple static getter. Ensure it is implemented in
`EntityActivationManager` as `public static long getGameTick()`.

---

## Constraints

- `pruneTick()` must only do work every 20 ticks (`currentTick % 20 == 0`), not every tick.
- When `ENABLE_SPAWN_LIMITER` is false, `isCoolingDown()` must return false immediately with no map reads.
- The `ConcurrentHashMap` is required because Forge's ServerChunkCache can call spawnForChunk from worker
  threads during async chunk promotion.
- Do NOT cancel spawns from spawner blocks, eggs, or commands — only natural spawning via NaturalSpawner.
- The mixin method names must be prefixed with `vc_` to avoid collision with other mods.

## Files to create/edit

- `src/main/java/com/velocitycore/system/SpawnRateLimiter.java`
- `src/main/java/com/velocitycore/mixin/MixinNaturalSpawner.java`
