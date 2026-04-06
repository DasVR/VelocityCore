# Prompt 03 — ServerTickHandler (Full Wiring)

## What to implement

Complete the full wiring of `ServerTickHandler.java`. By this point ChunkGenThrottle (S1) exists and the
config is loaded. This prompt finalises the tick handler so all future systems drop into it cleanly.

---

## ServerTickHandler.java (complete version)

**Package:** `com.velocitycore.event`

This class is the central event dispatcher for all server-side VelocityCore systems. Every system that needs to
do work per-tick receives a call from here — no system subscribes to tick events directly.

### Class-level annotation

```java
@Mod.EventBusSubscriber(modid = "velocitycore", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.DEDICATED_SERVER)
public class ServerTickHandler { ... }
```

### State field

```java
/** Monotonically incrementing game tick counter. Starts at 0 on server start. */
private static long gameTick = 0L;
```

### onServerTick — full implementation

```java
@SubscribeEvent
public static void onServerTick(TickEvent.ServerTickEvent event) {
    if (event.phase == TickEvent.Phase.START) {
        // Always update budget tracker first — even if TPS tracking is disabled,
        // beginTick() resets consumedNanos so hasBudget() returns correct results.
        ChunkGenThrottle.beginTick();
        return;
    }

    // Phase.END — run all systems in dependency order
    gameTick++;

    MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
    if (server == null) return;

    // S3: drain deferred decoration tasks (main-thread safe block writes)
    if (VCConfig.ENABLE_DEFERRED_DECORATOR.get()) {
        for (ServerLevel level : server.getAllLevels()) {
            DeferredDecorator.drainTick(level);
        }
    }

    // S5: prune stale cooldown entries from SpawnRateLimiter map
    if (VCConfig.ENABLE_SPAWN_LIMITER.get()) {
        SpawnRateLimiter.pruneTick(gameTick);
    }

    // S7: evict gone-cold chunks if the pruning interval has elapsed
    if (VCConfig.ENABLE_SMART_EVICTION.get()) {
        SmartEviction.pruneIfDue(gameTick);
    }

    // S6: issue read-ahead tickets for predicted player positions
    if (VCConfig.ENABLE_PRELOAD_RING.get()) {
        PreLoadRing.tick(server, gameTick);
    }

    // S4: advance the gameTick counter used by EntityActivationManager
    if (VCConfig.ENABLE_AI_THROTTLE.get()) {
        EntityActivationManager.setGameTick(gameTick);
    }
}
```

### onServerAboutToStart

```java
@SubscribeEvent
public static void onServerAboutToStart(ServerAboutToStartEvent event) {
    if (VCConfig.ENABLE_MOB_NORMALIZER.get()) {
        ModdedMobNormalizer.normalize(event.getServer());
    }
}
```

### onRegisterCommands

```java
@SubscribeEvent
public static void onRegisterCommands(RegisterCommandsEvent event) {
    // Register /velocitycore status and /velocitycore reload
    CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

    dispatcher.register(
        Commands.literal("velocitycore")
            .requires(src -> src.hasPermission(2))
            .then(Commands.literal("status")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(buildStatusReport()), false);
                    return 1;
                }))
            .then(Commands.literal("reload")
                .executes(ctx -> {
                    // Forge handles config reload; just confirm to operator
                    ctx.getSource().sendSuccess(() -> Component.literal("[VelocityCore] Config reloaded."), true);
                    return 1;
                }))
    );
}
```

### buildStatusReport (private helper)

```java
/**
 * Constructs a multi-line status string for the /velocitycore status command.
 * Lists TPS, budget, queue depths, active system count, and each system's enabled state.
 *
 * @return formatted status string
 */
private static String buildStatusReport() {
    // Count active systems
    int active = 0;
    // Increment active for each enabled VCConfig boolean...
    // Return assembled string with ChunkGenThrottle.getStatusString(),
    // DeferredDecorator.getQueueDepth(), SmartEviction.getHotChunkCount(), etc.
}
```

---

## Constraints

- `gameTick` must start at 0 and increment exactly once per `Phase.END` call.
- All system calls must be guarded individually — a disabled system must be a total no-op.
- Do not hold any references that prevent the server from garbage collecting after shutdown.
- The command must require permission level 2 (operator).
- Use `ServerLifecycleHooks.getCurrentServer()` to access the server — do not store it as a static field.

## Files to create/edit

- `src/main/java/com/velocitycore/event/ServerTickHandler.java`
