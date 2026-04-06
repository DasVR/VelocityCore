# Prompt 02 — ChunkGenThrottle + ServerTickHandler

## What to implement

Implement `ChunkGenThrottle.java` (S1) and wire it into `ServerTickHandler.java`. This is the heartbeat of
VelocityCore — every downstream system that does any work per tick first calls `ChunkGenThrottle.hasBudget()`
before proceeding.

---

## ChunkGenThrottle.java

**Package:** `com.velocitycore.system`

**Purpose:** Tracks a nanosecond budget per tick. Uses an exponential moving average (EMA) on measured tick
durations to derive a smoothed TPS value. Budget tiers scale dynamically based on that smoothed TPS.

### Fields

```java
/** EMA smoothing factor. Constant — do not make configurable. */
private static final double EMA_ALPHA = 0.1;

/** Nanoseconds at which the current tick started. Set by beginTick(). */
private static long tickStartNanos;

/** Nanoseconds consumed by gen work so far this tick. */
private static long consumedNanos;

/** Smoothed tick duration in nanoseconds (EMA). */
private static double smoothedTickNanos = 50_000_000.0; // start at 50ms (20 TPS)

/** Derived smoothed TPS (20.0 / (smoothedTickNanos / 50_000_000)). Updated each tick. */
private static volatile double smoothedTps = 20.0;
```

### Budget tiers

```java
/**
 * Returns the nanosecond budget for chunk generation work this tick, based on smoothed TPS.
 *
 * Tiers:
 *   TPS >= 19.5  →  6,000,000 ns  (6 ms)
 *   TPS >= 17.0  →  3,000,000 ns  (3 ms)
 *   TPS >= 15.0  →  1,000,000 ns  (1 ms)
 *   TPS  < 15.0  →    200,000 ns  (0.2 ms)
 */
private static long budgetNanos() { ... }
```

### Public API

```java
/**
 * Called at ServerTickEvent.Phase.START by ServerTickHandler.
 * Records the tick start time and resets consumed budget to zero.
 * Also updates the EMA from the previous tick's elapsed time.
 *
 * Must only be called on the main server thread.
 */
public static void beginTick() { ... }

/**
 * Returns true if there is remaining budget for generation work this tick.
 * Callers: DeferredDecorator.drainTick(), PreLoadRing.tick(), MixinServerChunkCache.
 *
 * Thread-safe read of volatile smoothedTps, but consumedNanos is main-thread-only.
 *
 * @return true if (consumedNanos < budgetNanos())
 */
public static boolean hasBudget() { ... }

/**
 * Charges nanoseconds against the current tick's budget.
 * Call this after each unit of generation work completes.
 *
 * @param nanos nanoseconds consumed by the work unit (measured with System.nanoTime())
 */
public static void charge(long nanos) { ... }

/**
 * Returns the current smoothed TPS as computed by the EMA.
 * Used by all TPS-scaled systems (SpawnRateLimiter, EntityActivationManager, MobCapGuard, etc.).
 *
 * @return smoothed TPS in range approximately [0, 20]
 */
public static double getSmoothedTps() { ... }

/**
 * Returns a human-readable status string for the /velocitycore status command.
 * Format: "TPS=18.4 budget=3ms consumed=1.2ms tier=MEDIUM"
 *
 * @return status string
 */
public static String getStatusString() { ... }
```

### TPS calculation

```
Each tick:
  elapsed = System.nanoTime() - tickStartNanos   (at beginTick, from previous tick end)
  smoothedTickNanos = (EMA_ALPHA * elapsed) + ((1 - EMA_ALPHA) * smoothedTickNanos)
  smoothedTps = 1_000_000_000.0 / smoothedTickNanos
  smoothedTps = Math.min(20.0, smoothedTps)
```

---

## ServerTickHandler.java

**Package:** `com.velocitycore.event`

**Purpose:** Receives `TickEvent.ServerTickEvent` and drives all server-side systems. Registered on the
`MinecraftForge.EVENT_BUS` with `@Mod.EventBusSubscriber(modid = "velocitycore", bus = Bus.FORGE, value = Dist.DEDICATED_SERVER)`.

### Method: onServerTick

```java
/**
 * Main server tick driver. Called every game tick on the main server thread.
 *
 * Phase.START: call ChunkGenThrottle.beginTick() first, then nothing else.
 * Phase.END:   run all system drain/tick methods in this order:
 *   1. DeferredDecorator.drainTick(level)     — if ENABLE_DEFERRED_DECORATOR
 *   2. SpawnRateLimiter.pruneTick()            — if ENABLE_SPAWN_LIMITER
 *   3. SmartEviction.pruneIfDue()             — if ENABLE_SMART_EVICTION
 *   4. PreLoadRing.tick(server)               — if ENABLE_PRELOAD_RING
 *   5. EntityActivationManager.tick()         — if ENABLE_AI_THROTTLE (updates gameTick counter)
 *
 * @param event the ServerTickEvent from Forge
 */
@SubscribeEvent
public static void onServerTick(TickEvent.ServerTickEvent event) { ... }
```

### Method: onServerAboutToStart

```java
/**
 * Fires after all mods have completed registration, before the world loads.
 * Calls ModdedMobNormalizer.normalize(event.getServer()) if ENABLE_MOB_NORMALIZER is true.
 *
 * @param event the ServerAboutToStartEvent from Forge
 */
@SubscribeEvent
public static void onServerAboutToStart(ServerAboutToStartEvent event) { ... }
```

### Status command (bonus, implement if time allows)

Register a simple `/velocitycore status` command in a `RegisterCommandsEvent` handler that prints:
- ChunkGenThrottle.getStatusString()
- DeferredDecorator queue depth
- SmartEviction hot chunk count
- Whether ModdedMobNormalizer has run

---

## Constraints

- `beginTick()` MUST be called at `Phase.START` — every downstream system assumes budget is reset.
- Do NOT call `System.currentTimeMillis()` anywhere — use `System.nanoTime()` exclusively.
- The EMA calculation must use the previous tick's elapsed time, not the current tick's start time.
- All system calls in `Phase.END` must be individually guarded by their VCConfig boolean.
- If `VCConfig.ENABLE_TPS_TRACKING` is false, `ChunkGenThrottle.getSmoothedTps()` must return a fixed
  20.0 and `hasBudget()` must always return true.

## Files to create/edit

- `src/main/java/com/velocitycore/system/ChunkGenThrottle.java`
- `src/main/java/com/velocitycore/event/ServerTickHandler.java`
