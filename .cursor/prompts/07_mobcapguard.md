# Prompt 07 — MobCapGuard + PathfindingThrottle + MixinPathFinder (S11, S12)

## What to implement

Implement `MobCapGuard.java` (S11) and `PathfindingThrottle.java` (S12) and wire the pathfinding throttle
into `MixinPathFinder.java`. MobCapGuard is already injected via `MixinNaturalSpawner` (prompt 04) — this
prompt implements the system class itself.

---

## MobCapGuard.java

**Package:** `com.velocitycore.system`

**Purpose:** Adds a per-category soft mob cap that scales with TPS. When the server is healthy, caps are
loose. Under stress they tighten to protect tick time. Soft caps only restrict new natural spawning — existing
entities are never removed.

### Default soft caps

```java
private static final Map<MobCategory, Integer> BASE_CAPS = Map.of(
    MobCategory.MONSTER,         70,
    MobCategory.CREATURE,        10,
    MobCategory.AMBIENT,         15,
    MobCategory.WATER_CREATURE,   5,
    MobCategory.WATER_AMBIENT,   20,
    MobCategory.MISC,             5
);
```

### Public API

```java
/**
 * Returns true if the given category has met or exceeded its soft cap in the given level.
 * Called from MixinNaturalSpawner.vc_mobCapGuardCheck().
 *
 * If VCConfig.ENABLE_MOB_CAP_GUARD is false, always returns false (never block).
 *
 * @param category the MobCategory being evaluated
 * @param level the ServerLevel to count entities in
 * @return true if spawning for this category should be blocked
 */
public static boolean isCategoryOver(MobCategory category, ServerLevel level) {
    if (!VCConfig.ENABLE_MOB_CAP_GUARD.get()) return false;
    int softCap = effectiveCap(category);
    int current = level.getEntities(EntityTypeTest.forClass(Entity.class), e -> {
        return e instanceof Mob m && m.getType().getCategory() == category;
    }).size();
    return current >= softCap;
}

/**
 * Computes the effective soft cap for the given category based on current TPS.
 * At >= 19 TPS: use BASE_CAPS value.
 * At < 15 TPS: halve the base cap (minimum 2).
 * Between 15 and 19: linear interpolation (optional — simple halving is acceptable).
 *
 * @param category the MobCategory
 * @return effective soft cap
 */
private static int effectiveCap(MobCategory category) {
    int base = BASE_CAPS.getOrDefault(category, 10);
    double tps = ChunkGenThrottle.getSmoothedTps();
    if (tps < 15.0) return Math.max(2, base / 2);
    return base;
}
```

---

## PathfindingThrottle.java

**Package:** `com.velocitycore.system`

**Purpose:** Caps the number of nodes the A* pathfinder evaluates per tick for each mob, based on the mob's
distance from the nearest player and the current TPS. Uses a `ThreadLocal<Integer>` to pass the budget into
the MixinPathFinder injection without requiring parameter modification.

### Node budgets

```
Zone        Distance        Healthy budget    Stressed budget (TPS < 15)
NEAR        0–32 blocks     unlimited         unlimited
MEDIUM      32–64 blocks    64 nodes          32 nodes
FAR         > 64 blocks     24 nodes          12 nodes
```

"Unlimited" means `Integer.MAX_VALUE` — the mixin check always passes.

### Fields

```java
/**
 * Thread-local node budget for the current pathfinding call.
 * Set by prepareBudget() before PathFinder.findPath() is called.
 * Read by MixinPathFinder on each node evaluation.
 */
private static final ThreadLocal<Integer> nodeBudget = ThreadLocal.withInitial(() -> Integer.MAX_VALUE);

/**
 * Thread-local counter of nodes evaluated in the current pathfinding call.
 * Reset to 0 by prepareBudget().
 */
private static final ThreadLocal<Integer> nodeCount = ThreadLocal.withInitial(() -> 0);
```

### Public API

```java
/**
 * Sets up the per-call node budget for a mob about to pathfind.
 * Called from MixinPathFinder at the start of PathFinder.findPath().
 *
 * If VCConfig.ENABLE_PATHFINDING_THROTTLE is false, sets budget to MAX_VALUE.
 *
 * @param mob the mob initiating pathfinding (may be null if not accessible — use MAX_VALUE)
 */
public static void prepareBudget(Mob mob) {
    nodeCount.set(0);
    if (!VCConfig.ENABLE_PATHFINDING_THROTTLE.get() || mob == null) {
        nodeBudget.set(Integer.MAX_VALUE);
        return;
    }
    double distSq = nearestPlayerDistanceSq(mob);
    nodeBudget.set(budgetForDistance(distSq, ChunkGenThrottle.getSmoothedTps()));
}

/**
 * Increments the node counter and returns true if the budget is exhausted.
 * Called from MixinPathFinder on each node evaluation step.
 *
 * @return true if pathfinding should be halted (budget exceeded)
 */
public static boolean exceedsBudget() {
    int count = nodeCount.get() + 1;
    nodeCount.set(count);
    return count > nodeBudget.get();
}

/** Computes budget based on squared distance and TPS. */
private static int budgetForDistance(double distSq, double tps) {
    boolean stressed = tps < 15.0;
    if (distSq <= 32 * 32) return Integer.MAX_VALUE;
    if (distSq <= 64 * 64) return stressed ? 32 : 64;
    return stressed ? 12 : 24;
}

private static double nearestPlayerDistanceSq(Mob mob) {
    if (!(mob.level() instanceof ServerLevel sl)) return 0;
    Player nearest = sl.getNearestPlayer(mob, -1);
    return nearest == null ? 0 : mob.distanceToSqr(nearest);
}
```

---

## MixinPathFinder.java

**Package:** `com.velocitycore.mixin`

**Target class:** `net.minecraft.world.entity.ai.navigation.PathFinder`

**Purpose:** Injects at the start of `findPath` to set up the budget, and inside the node evaluation loop to
check `exceedsBudget()` and short-circuit the search.

### Injection — findPath HEAD

```java
// Injection point rationale: @At("HEAD") on PathFinder.findPath() is where we set up the per-call
// node budget using PathfindingThrottle.prepareBudget(). We cannot pass the mob reference through
// the vanilla PathFinder signature so we store it in a ThreadLocal and read it here.
// The mob reference is obtained by walking up to the PathNavigation that owns this PathFinder,
// but if that is not accessible, we pass null and PathfindingThrottle defaults to MAX_VALUE.
@Inject(
    method = "findPath",
    at = @At("HEAD")
)
private void vc_setupPathBudget(BlockPathTypes[] pathTypes, Entity entity, int maxVisitedNodes, CallbackInfoReturnable<Path> cir) {
    // entity may be the mob itself if the signature matches — cast carefully
    Mob mob = (entity instanceof Mob m) ? m : null;
    PathfindingThrottle.prepareBudget(mob);
}
```

### Injection — node evaluation loop

The A* evaluation loop in `PathFinder` is the inner `while(!openSet.isEmpty())` loop. Inject at the loop head:

```java
// Injection point rationale: injecting at the BinaryHeap poll call inside the A* loop allows us to
// intercept each node evaluation without modifying the algorithm. The INVOKE target is the
// BinaryHeap.pop() or equivalent method called at the top of each loop iteration.
// We cancel the entire path search (return null) when budget is exceeded, which is safe because
// PathFinder returns null to indicate "no path found" in vanilla already.
@Inject(
    method = "findPath",
    at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/pathfinder/BinaryHeap;pop()Lnet/minecraft/world/level/pathfinder/Node;"),
    cancellable = true
)
private void vc_checkNodeBudget(BlockPathTypes[] pathTypes, Entity entity, int maxVisitedNodes, CallbackInfoReturnable<Path> cir) {
    if (PathfindingThrottle.exceedsBudget()) {
        cir.setReturnValue(null);
    }
}
```

**Important:** The exact method signature and INVOKE target for the BinaryHeap pop must be verified against
the Forge 1.20.1 decompiled sources. Use the mojmap name `BinaryHeap` and confirm the class path is
`net.minecraft.world.level.pathfinder.BinaryHeap`. If the loop structure differs, adjust the `@At` target
accordingly — the goal is to fire once per node popped from the open set.

---

## Constraints

- `MobCapGuard.isCategoryOver()` must never scan entities in a blocking way on the main thread — use
  `Level.getEntities()` with a filtered predicate, which is O(n) but safe.
- `PathfindingThrottle` uses `ThreadLocal` because `PathFinder.findPath()` can be called from worldgen
  threads in some Forge versions.
- When `ENABLE_PATHFINDING_THROTTLE` is false, `prepareBudget()` must set `Integer.MAX_VALUE` so
  `exceedsBudget()` never triggers — zero overhead on the hot path.
- MobCapGuard soft caps are per-level, not global — pass the ServerLevel from the mixin.
- Soft caps apply to natural spawning only — spawner blocks and commands are unaffected.

## Files to create/edit

- `src/main/java/com/velocitycore/system/MobCapGuard.java`
- `src/main/java/com/velocitycore/system/PathfindingThrottle.java`
- `src/main/java/com/velocitycore/mixin/MixinPathFinder.java`
