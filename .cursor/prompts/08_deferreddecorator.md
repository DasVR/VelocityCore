# Prompt 08 — DeferredDecorator + SmartChunkCache + MixinNoiseBasedChunkGenerator (S3, S2)

## What to implement

Implement `DeferredDecorator.java` (S3), `SmartChunkCache.java` (S2), and the injection points in
`MixinNoiseBasedChunkGenerator.java`. These two systems are specifically tuned for WWEE's heavy decoration
and 200-biome noise sampling load.

---

## DeferredDecorator.java

**Package:** `com.velocitycore.system`

**Purpose:** Feature decoration (trees, fallen logs, stone piles, WWEE custom vegetation) is the single most
expensive per-chunk operation. This system enqueues decoration tasks into a wait-free queue and drains them
across multiple ticks within ChunkGenThrottle's budget window. All draining runs on the main server thread.

### Inner class: DecorationTask

```java
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
```

### Fields

```java
/**
 * Wait-free decoration task queue. Enqueue is called from worldgen threads;
 * drain is called from the main server thread only.
 */
private static final ConcurrentLinkedQueue<DecorationTask> queue = new ConcurrentLinkedQueue<>();
```

### Public API

```java
/**
 * Enqueues a decoration task. Called from MixinNoiseBasedChunkGenerator instead of
 * running decoration inline. May be called from any worldgen thread.
 *
 * If VCConfig.ENABLE_DEFERRED_DECORATOR is false, runs decoration immediately inline instead.
 *
 * @param level     the WorldGenLevel for this chunk
 * @param generator the ChunkGenerator performing decoration
 * @param chunkPos  the chunk position to decorate
 */
public static void enqueue(WorldGenLevel level, ChunkGenerator generator, ChunkPos chunkPos) {
    if (!VCConfig.ENABLE_DEFERRED_DECORATOR.get()) {
        // Run inline — disabled mode must be identical to vanilla behaviour
        try {
            generator.applyBiomeDecoration(level, level.getChunk(chunkPos.x, chunkPos.z), level.structureManager());
        } catch (Exception e) {
            LogManager.getLogger("VelocityCore/DeferredDecorator").warn("Inline decoration failed at {}: {}", chunkPos, e.getMessage());
        }
        return;
    }
    queue.offer(new DecorationTask(level, generator, chunkPos));
}

/**
 * Drains pending decoration tasks within ChunkGenThrottle's budget for this tick.
 * Called by ServerTickHandler in Phase.END for each ServerLevel.
 *
 * Each task is timed with System.nanoTime(); the elapsed time is charged to the budget.
 * Exceptions per task are caught, logged, and skipped — a bad decoration never crashes the server.
 *
 * @param level the ServerLevel being ticked (tasks are not filtered by level — all drain together)
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
            LogManager.getLogger("VelocityCore/DeferredDecorator")
                .warn("Decoration failed at {}: {}", task.chunkPos, e.getMessage());
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
public static int getQueueDepth() { return queue.size(); }
```

---

## SmartChunkCache.java

**Package:** `com.velocitycore.system`

**Purpose:** WWEE's 200-biome noise sampler hits the same (x, z) coordinates repeatedly as adjacent chunks
share edge samples. Each worldgen worker thread maintains its own ThreadLocal LRU cache keyed on a packed
long. Zero contention on the hot path.

### Cache key packing

```java
/**
 * Packs chunk sample coordinates and sampler type into a single long key.
 * No object allocation — just bit ops.
 *
 * Layout: bits 63-40 = x (24 bits), bits 39-8 = z (24 bits), bits 7-0 = type
 *
 * @param x    block or chunk x coordinate (truncated to 24 bits)
 * @param z    block or chunk z coordinate (truncated to 24 bits)
 * @param type sampler type discriminator (0=biome/climate, 1=noise scalar)
 * @return packed long key
 */
public static long packKey(int x, int z, int type) {
    return ((long)(x & 0xFFFFFF) << 40) | ((long)(z & 0xFFFFFF) << 8) | (type & 0xFF);
}
```

### ThreadLocal cache

```java
/**
 * One LRU LinkedHashMap per worldgen thread. Access count = 0 contention.
 * Holds Object values — caller casts to the expected type (Climate.TargetPoint or double[]).
 */
private static final ThreadLocal<LinkedHashMap<Long, Object>> cache = ThreadLocal.withInitial(() ->
    new LinkedHashMap<>(VCConfig.NOISE_CACHE_SIZE.get(), 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Object> eldest) {
            return size() > VCConfig.NOISE_CACHE_SIZE.get();
        }
    }
);
```

### Public API

```java
/**
 * Looks up a cached sample. Returns null on cache miss.
 *
 * @param key packed long key from packKey()
 * @return cached value or null
 */
public static Object get(long key) {
    if (!VCConfig.ENABLE_NOISE_CACHE.get()) return null;
    return cache.get().get(key);
}

/**
 * Inserts a sample into the cache.
 *
 * @param key   packed long key from packKey()
 * @param value the sample value (Climate.TargetPoint or double[])
 */
public static void put(long key, Object value) {
    if (!VCConfig.ENABLE_NOISE_CACHE.get()) return;
    cache.get().put(key, value);
}
```

---

## MixinNoiseBasedChunkGenerator.java

**Package:** `com.velocitycore.mixin`

**Target class:** `net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator`

**Purpose:** Two injections: (1) intercept `applyBiomeDecoration` to enqueue into DeferredDecorator instead
of running inline; (2) intercept noise/biome sampling calls to check SmartChunkCache first.

### Injection 1 — Defer decoration

```java
// Injection point rationale: @At("HEAD") with cancellable=true on applyBiomeDecoration() replaces
// the entire decoration call with a queue enqueue. This is safe because decoration is idempotent
// per-chunk — the chunk being queued holds all state needed to replay decoration later.
// We do NOT cancel when ENABLE_DEFERRED_DECORATOR is false — enqueue() handles the fallback.
@Inject(
    method = "applyBiomeDecoration",
    at = @At("HEAD"),
    cancellable = true
)
private void vc_deferDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager, CallbackInfo ci) {
    if (VCConfig.ENABLE_DEFERRED_DECORATOR.get()) {
        DeferredDecorator.enqueue(level, (ChunkGenerator)(Object)this, chunk.getPos());
        ci.cancel();
    }
}
```

### Injection 2 — Noise sample cache

The target method is the biome sample call site inside `buildSurface` or `fillFromNoise`. The exact method
to target depends on the 1.20.1 decompilation. The goal is to wrap the `Climate.Sampler.sample(x, y, z)` call:

```java
// Injection point rationale: @At(INVOKE) targeting Climate$Sampler.sample() allows us to intercept
// every biome noise evaluation. We check SmartChunkCache before the call and store the result after.
// Using @ModifyVariable or @Redirect would be cleaner but @Inject with cancellable=true is safer
// for cross-mod compatibility. If the cache returns a hit, cancel the vanilla call and return cached.
//
// NOTE: Implement this injection only if you can confirm the exact INVOKE target in the 1.20.1
// sources. If the method is inlined or the call site differs, use @ModifyVariable instead.
```

Implement the cache injection using whatever injection type is most appropriate after inspecting the 1.20.1
decompiled `NoiseBasedChunkGenerator` source. The key constraint is: on a cache hit, avoid calling the
vanilla sampler. On a cache miss, call vanilla, cache the result, and return it.

---

## Constraints

- `DeferredDecorator.drainTick()` MUST only be called from the main server thread (ServerTickHandler Phase.END).
  Never call it from worldgen threads or Forge world events.
- Exception handling in `drainTick()` must be per-task — one bad task must never abort the drain loop.
- `SmartChunkCache` uses `Object` for cache values to avoid two separate ThreadLocals. Callers must cast.
- When `ENABLE_NOISE_CACHE` is false, `get()` returns null and `put()` is a no-op — zero overhead.
- The decoration mixin MUST cancel the vanilla applyBiomeDecoration only when the system is enabled.
  When disabled, vanilla decoration must proceed unmodified.

## Files to create/edit

- `src/main/java/com/velocitycore/system/DeferredDecorator.java`
- `src/main/java/com/velocitycore/system/SmartChunkCache.java`
- `src/main/java/com/velocitycore/mixin/MixinNoiseBasedChunkGenerator.java`
