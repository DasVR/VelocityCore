# Prompt 05 — EntityActivationManager + MixinMob (S4)

## What to implement

Implement `EntityActivationManager.java` (S4) and its injection point in `MixinMob.java`. This is the second
fastest visible server-side win after SpawnRateLimiter — expect mob tick time in spark to drop noticeably.

---

## EntityActivationManager.java

**Package:** `com.velocitycore.system`

**Purpose:** Dynamically adjusts how frequently each mob is ticked based on two factors:
1. Distance to the nearest player in the mob's level
2. Current smoothed TPS from ChunkGenThrottle

Mobs close to players always tick at full rate. Distant mobs tick less frequently under stress. Load is
distributed evenly across the interval window using the mob's entity ID as an offset so all mobs at the
same distance don't skip on the same tick simultaneously.

### Distance zones and intervals

```
Zone        Distance        Healthy interval    Stressed interval (TPS < 15)
NEAR        0–32 blocks     1 (always tick)     1 (always tick — no compromise)
MEDIUM      32–64 blocks    2                   4
FAR         64–96 blocks    4                   8
DISTANT     > 96 blocks     6                   12
```

"Healthy" = TPS >= 17.0. "Stressed" = TPS < 15.0. Between 15 and 17, use the healthy interval.

### Fields

```java
/** Current game tick counter. Synced from ServerTickHandler.gameTick each Phase.END. */
private static volatile long gameTick = 0L;
```

### Public API

```java
/**
 * Called by ServerTickHandler each Phase.END to update the game tick counter.
 *
 * @param tick the current game tick from ServerTickHandler
 */
public static void setGameTick(long tick) { gameTick = tick; }

/**
 * Returns the current game tick. Used by MixinNaturalSpawner and SpawnRateLimiter.
 *
 * @return current game tick
 */
public static long getGameTick() { return gameTick; }

/**
 * Determines whether the given mob should tick this game tick.
 *
 * Called from MixinMob at the HEAD of Mob.tick().
 * Returns false (tick should be skipped) if:
 *   - VCConfig.ENABLE_AI_THROTTLE is true
 *   - The mob is not in the NEAR zone
 *   - (gameTick % interval) != (mob.getId() % interval)   [offset stagger]
 *
 * Always returns true if:
 *   - VCConfig.ENABLE_AI_THROTTLE is false
 *   - The mob is in the NEAR zone (distance <= 32)
 *   - The mob's level has no players (conservative fallback)
 *
 * @param mob the mob being evaluated
 * @return true if the mob should tick normally, false if its tick should be skipped
 */
public static boolean shouldTick(Mob mob) {
    if (!VCConfig.ENABLE_AI_THROTTLE.get()) return true;

    Level level = mob.level();
    if (!(level instanceof ServerLevel serverLevel)) return true;

    // Find nearest player distance
    double nearestDist = nearestPlayerDistanceSq(serverLevel, mob);
    if (nearestDist < 0) return true; // no players — conservative, always tick

    int interval = intervalForDistance(nearestDist, ChunkGenThrottle.getSmoothedTps());
    if (interval <= 1) return true;

    // Stagger: (gameTick % interval) == (mob.getId() % interval)
    return (gameTick % interval) == (Math.abs(mob.getId()) % interval);
}
```

### Private helpers

```java
/**
 * Returns the squared distance to the nearest player in the level.
 * Returns -1 if no players are present.
 *
 * Uses serverLevel.getNearestPlayer(mob, -1) for efficiency.
 */
private static double nearestPlayerDistanceSq(ServerLevel level, Mob mob) { ... }

/**
 * Maps squared distance + TPS to the correct tick interval.
 *
 * @param distSq   squared distance to nearest player in blocks
 * @param tps      smoothed TPS from ChunkGenThrottle
 * @return tick interval (1 = always tick, 12 = max throttle)
 */
private static int intervalForDistance(double distSq, double tps) {
    boolean stressed = tps < 15.0;
    if (distSq <= 32 * 32)   return 1;
    if (distSq <= 64 * 64)   return stressed ? 4 : 2;
    if (distSq <= 96 * 96)   return stressed ? 8 : 4;
    return stressed ? 12 : 6;
}
```

---

## MixinMob.java

**Package:** `com.velocitycore.mixin`

**Target class:** `net.minecraft.world.entity.Mob`

**Purpose:** Intercepts `Mob.tick()` at HEAD to allow EntityActivationManager to skip the tick for distant or
stressed mobs.

### Injection

```java
// Injection point rationale: @At("HEAD") with cancellable=true on Mob.tick() is the earliest possible
// point to skip the entire mob tick. Injecting here means zero work is done for throttled mobs —
// no goal evaluation, no AI, no physics. The @Inject on Mob (not LivingEntity or Entity) ensures
// only Mob subclasses are throttled, not players, projectiles, or other non-mob entities.
@Inject(
    method = "tick",
    at = @At("HEAD"),
    cancellable = true
)
private void vc_activationCheck(CallbackInfo ci) {
    if (!EntityActivationManager.shouldTick((Mob)(Object)this)) {
        ci.cancel();
    }
}
```

### Accessor for getId

No accessor is needed — `Entity.getId()` is public in Mojmap.

---

## Constraints

- The `shouldTick` check must be a total no-op (return true immediately) when `ENABLE_AI_THROTTLE` is false.
- The stagger formula `(gameTick % interval) == (Math.abs(mob.getId()) % interval)` must use
  `Math.abs()` to handle negative entity IDs safely.
- Never skip ticks for mobs in the NEAR zone (0–32 blocks) — player-adjacent mobs must always be full rate.
- If a mob's level is client-side or null, return true (conservative — always tick).
- `nearestPlayerDistanceSq` should use the mob's position as the search origin and -1 for the max range
  parameter to search the entire level.
- Do NOT cancel the super call chain in `MixinMob` — only cancel the specific `tick()` invocation.

## Files to create/edit

- `src/main/java/com/velocitycore/system/EntityActivationManager.java`
- `src/main/java/com/velocitycore/mixin/MixinMob.java`
