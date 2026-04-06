# Prompt 09 — PreLoadRing + SmartEviction + RegionFileBuffer (S6, S7, S9)

## What to implement

Implement `PreLoadRing.java` (S6), `SmartEviction.java` (S7), and `RegionFileBuffer.java` (S9). These three
systems work together to make chunk I/O as fast as possible in a Chunky pre-generated world. All require a
Chunky-baked world to be effective — they degrade gracefully to no-ops when chunks are not yet FULL.

---

## PreLoadRing.java

**Package:** `com.velocitycore.system`

**Purpose:** Tracks each player's position delta across 5 ticks to derive a velocity vector, then pre-issues
UNKNOWN-type chunk load tickets for the likely next N chunks. Only issues tickets for chunks that are
confirmed ChunkStatus.FULL on disk. Falls back to DeferredDecorator for unconfirmed chunks.

### Movement speed tiers

```
MovementType    Read-ahead chunks
WALK            2
SPRINT          3
HORSE           4
ELYTRA          6
BOAT            3
```

These match `VCNetworkChannel.VelocityHintPacket.MovementType`.

### Per-player state

```java
private static final Map<UUID, PlayerVelocityState> playerStates = new ConcurrentHashMap<>();

private static class PlayerVelocityState {
    final Vec3[] posHistory = new Vec3[5];  // ring buffer, positions over last 5 ticks
    int head = 0;
    float vx, vy, vz;
    VCNetworkChannel.VelocityHintPacket.MovementType movementType =
        VCNetworkChannel.VelocityHintPacket.MovementType.WALK;
    // hint blending: server-estimated velocity blended with client hint (alpha=0.7 for client hint)
}
```

### Public API

```java
/**
 * Per-tick update called by ServerTickHandler in Phase.END.
 * For each online player:
 *   1. Records current position in history ring buffer
 *   2. Computes velocity from (pos[head] - pos[(head-5+5)%5]) / 5
 *   3. Blends with client hint if received this tick
 *   4. Issues chunk load tickets ahead of predicted travel
 *
 * @param server   the MinecraftServer
 * @param gameTick current game tick (for budget checks)
 */
public static void tick(MinecraftServer server, long gameTick) {
    if (!VCConfig.ENABLE_PRELOAD_RING.get()) return;
    if (!ChunkGenThrottle.hasBudget()) return;
    // ...
}

/**
 * Called by VCNetworkChannel when a VelocityHintPacket is received from a client.
 * Blends the client's precise velocity with the server's estimate.
 * Alpha = 0.7 in favour of the client hint.
 *
 * @param player       the server-side player entity
 * @param vx, vy, vz   client velocity in blocks/tick
 * @param movementType client-reported movement type
 */
public static void receiveVelocityHint(ServerPlayer player, float vx, float vy, float vz,
        VCNetworkChannel.VelocityHintPacket.MovementType movementType) {
    // ...
}

/**
 * Removes state for a player who has left the server.
 * Called from a PlayerEvent.PlayerLoggedOutEvent handler in ServerTickHandler.
 *
 * @param playerId the UUID of the departing player
 */
public static void removePlayer(UUID playerId) {
    playerStates.remove(playerId);
}
```

### Chunky compatibility check

Before issuing any ticket, verify the chunk is FULL on disk:

```java
/**
 * Returns true if the chunk at (chunkX, chunkZ) in the given level has ChunkStatus.FULL on disk.
 *
 * Implementation:
 *   1. Get the region file path from level.getChunkSource().chunkMap.
 *   2. Read the chunk's NBT from the .mca file (use RegionFileStorage or direct NBT read).
 *   3. Check the "Status" string tag == ChunkStatus.FULL.getName() ("minecraft:full").
 *   4. Cache the result for 60 seconds to avoid repeated disk reads.
 *
 * If the check fails for any reason (IO error, missing file), return false conservatively.
 *
 * @param level  the ServerLevel
 * @param chunkX chunk X
 * @param chunkZ chunk Z
 * @return true if the chunk is confirmed FULL on disk
 */
private static boolean isChunkFullOnDisk(ServerLevel level, int chunkX, int chunkZ) { ... }
```

### Ticket issuance

```java
/**
 * Issues a UNKNOWN-type chunk ticket for the given coordinates.
 * Uses TicketType.UNKNOWN with TTL of 300 ticks (15 seconds).
 * Never issues tickets inside the world border for non-FULL chunks.
 *
 * @param level  the ServerLevel
 * @param chunkX chunk X
 * @param chunkZ chunk Z
 */
private static void issueTicket(ServerLevel level, int chunkX, int chunkZ) {
    ChunkPos pos = new ChunkPos(chunkX, chunkZ);
    // Verify world border
    WorldBorder border = level.getWorldBorder();
    if (!border.isWithinBounds(pos)) return;
    // Verify FULL status
    if (!isChunkFullOnDisk(level, chunkX, chunkZ)) {
        // Fall back to DeferredDecorator for generation
        return;
    }
    level.getChunkSource().addRegionTicket(TicketType.UNKNOWN, pos, 1, pos);
}
```

---

## SmartEviction.java

**Package:** `com.velocitycore.system`

**Purpose:** Tracks chunk access frequency using a counter per ChunkPos. Hot chunks (accessed more than 20
times in the last 10 minutes) are kept alive in memory at border-level even without a nearby player. Prevents
the player's base area from being evicted and requiring a full disk re-read on return.

### Fields

```java
/** Maps ChunkPos.toLong() → access count since last decay. */
private static final ConcurrentHashMap<Long, AtomicInteger> accessCounts = new ConcurrentHashMap<>();

/** Timestamp of last decay pass (in game ticks). */
private static long lastDecayTick = 0L;
/** Decay interval: 12000 ticks = 10 minutes. */
private static final long DECAY_INTERVAL_TICKS = 12_000L;
/** Prune interval: 1200 ticks = 60 seconds. */
private static final long PRUNE_INTERVAL_TICKS = 1_200L;
/** Hot access threshold: more than this many accesses keeps a chunk hot. */
private static final int HOT_THRESHOLD = 20;
```

### Public API

```java
/**
 * Records an access to a chunk. Increments its counter.
 * Called from MixinServerChunkCache when a chunk is accessed for entity ticking.
 *
 * @param chunkPos the accessed chunk position
 */
public static void recordAccess(ChunkPos chunkPos) {
    if (!VCConfig.ENABLE_SMART_EVICTION.get()) return;
    accessCounts.computeIfAbsent(chunkPos.toLong(), k -> new AtomicInteger(0)).incrementAndGet();
}

/**
 * Returns true if the given chunk should be kept hot in memory.
 * Used by MixinServerChunkCache to override vanilla eviction for this chunk.
 *
 * @param chunkPos the chunk to check
 * @return true if the chunk has a hot access count
 */
public static boolean isHot(ChunkPos chunkPos) {
    if (!VCConfig.ENABLE_SMART_EVICTION.get()) return false;
    AtomicInteger counter = accessCounts.get(chunkPos.toLong());
    return counter != null && counter.get() > HOT_THRESHOLD;
}

/**
 * Prunes and decays the access counter map. Called by ServerTickHandler.
 * Decay runs every DECAY_INTERVAL_TICKS (halves all counters).
 * Prune runs every PRUNE_INTERVAL_TICKS (removes zero-count entries and enforces HOT_CHUNK_MAX).
 *
 * @param gameTick current game tick
 */
public static void pruneIfDue(long gameTick) { ... }

/**
 * Returns the count of chunks currently in the hot set.
 * Used by /velocitycore status.
 *
 * @return hot chunk count
 */
public static int getHotChunkCount() {
    return (int) accessCounts.values().stream().filter(c -> c.get() > HOT_THRESHOLD).count();
}
```

---

## RegionFileBuffer.java

**Package:** `com.velocitycore.system`

**Purpose:** When any chunk from an .mca region file is requested, speculatively reads the surrounding sector
cluster into a heap buffer on a background I/O thread. This converts random disk reads to sequential reads for
the adjacent chunks that are very likely to be needed soon.

### Fields

```java
/** Background I/O thread for region file warming. Never writes world data. */
private static final ExecutorService ioThread = Executors.newSingleThreadExecutor(
    r -> { Thread t = new Thread(r, "VelocityCore-RegionIO"); t.setDaemon(true); return t; }
);

/** Tracks which region files have been recently warmed to avoid redundant work. */
private static final Cache<String, Boolean> warmedRegions = Caffeine.newBuilder()
    .expireAfterWrite(30, TimeUnit.SECONDS)
    .maximumSize(256)
    .build();
```

Note: If Caffeine is not available, use a `ConcurrentHashMap<String, Long>` with manual expiry via
`System.nanoTime()` comparison in `isWarmed()`.

### Public API

```java
/**
 * Called when a chunk is first requested from an .mca region file.
 * Submits a background task to warm the surrounding sector cluster if not already warmed.
 *
 * If VCConfig.ENABLE_REGION_BUFFER is false, does nothing.
 *
 * @param level    the ServerLevel containing the region file
 * @param chunkPos the chunk being read (used to identify the region file)
 */
public static void onChunkRead(ServerLevel level, ChunkPos chunkPos) {
    if (!VCConfig.ENABLE_REGION_BUFFER.get()) return;
    String regionKey = regionKey(chunkPos);
    if (isWarmed(regionKey)) return;
    markWarmed(regionKey);
    ioThread.submit(() -> warmRegionFile(level, chunkPos));
}

/**
 * Background task: reads up to VCConfig default 16 sectors from the region file
 * surrounding the requested chunk into a discarded heap buffer.
 * This primes the OS page cache for adjacent chunk reads.
 *
 * IMPORTANT: This method ONLY reads data. It never writes to the world.
 *
 * @param level    the ServerLevel
 * @param chunkPos the triggering chunk position
 */
private static void warmRegionFile(ServerLevel level, ChunkPos chunkPos) { ... }

/** Returns a string key for the region file containing chunkPos. */
private static String regionKey(ChunkPos pos) {
    return (pos.x >> 5) + "," + (pos.z >> 5);
}
```

---

## Constraints

**PreLoadRing:**
- NEVER issue a ticket for a chunk outside the WorldBorder — check `border.isWithinBounds(pos)`.
- NEVER issue a PLAYER or POST_TELEPORT ticket — only TicketType.UNKNOWN.
- If `isChunkFullOnDisk` returns false, silently skip the ticket — do not trigger generation.
- The disk status cache must have a TTL to handle Chunky completing mid-session.

**SmartEviction:**
- Hot chunk ceiling (`HOT_CHUNK_MAX`) must be enforced — if hot set exceeds the limit, evict the coldest.
- Counter decay must halve counts, not zero them — gradual cooling prevents thrashing.
- `AtomicInteger` is required because `recordAccess` may be called from multiple threads.

**RegionFileBuffer:**
- The I/O thread is daemon=true so it does not prevent JVM shutdown.
- The warm task reads bytes into a `byte[]` buffer and immediately discards it — it just primes the OS cache.
- Never call `warmRegionFile` on the main server thread — always submit to `ioThread`.

## Files to create/edit

- `src/main/java/com/velocitycore/system/PreLoadRing.java`
- `src/main/java/com/velocitycore/system/SmartEviction.java`
- `src/main/java/com/velocitycore/system/RegionFileBuffer.java`
