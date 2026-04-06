# Prompt 01 — VCConfig + VCNetworkChannel

## What to implement

Implement `VCConfig.java` and `VCNetworkChannel.java`. These two classes are the foundation of the entire
mod — every system reads VCConfig before doing any work, and VCNetworkChannel is registered once in the
@Mod constructor.

---

## VCConfig.java

**Package:** `com.velocitycore.config`

**Purpose:** Forge `ModConfigSpec`-based configuration holder. Declares every config key for all 15 systems in
a single class. Two separate configs are produced: a server config (`velocitycore-server.toml`) and a client
config (`velocitycore-client.toml`). The server config is reloadable at runtime via `/velocitycore reload`.

### Server config keys and types

Declare all of the following as `ForgeConfigSpec.BooleanValue` or `ForgeConfigSpec.IntValue` using a
`ForgeConfigSpec.Builder`. Group them with `builder.push("section_name")` / `builder.pop()`.

```
SECTION: general
  enable_tps_tracking          BooleanValue  default=true
    comment: "Master toggle for the TPS measurement loop. Disabling this also disables all TPS-scaled behaviour."

SECTION: chunk_systems
  enable_gen_throttle          BooleanValue  default=true
    comment: "S1: Enable TPS-aware chunk generation budget. Prevents gen from monopolising tick time."
  enable_noise_cache           BooleanValue  default=true
    comment: "S2: Enable per-thread LRU noise/biome sample cache. Eliminates redundant WWEE noise calls."
  noise_cache_size             IntValue      default=4096  range=[256, 65536]
    comment: "S2: Maximum cache entries per worldgen thread."
  enable_deferred_decorator    BooleanValue  default=true
    comment: "S3: Spread WWEE feature decoration across multiple ticks instead of all at once."
  decoration_per_tick          IntValue      default=4     range=[1, 20]
    comment: "S3: Maximum decoration tasks drained per tick."
  enable_preload_ring          BooleanValue  default=true
    comment: "S6: Velocity-based chunk read-ahead. Requires a Chunky pre-generated world."
  enable_smart_eviction        BooleanValue  default=true
    comment: "S7: Keep frequently-accessed chunks in memory using LFU eviction."
  hot_chunk_max                IntValue      default=512   range=[64, 4096]
    comment: "S7: Maximum number of hot chunks to keep in memory beyond normal view distance."
  enable_fast_path             BooleanValue  default=true
    comment: "S8: Skip redundant pipeline stages for ChunkStatus.FULL chunks."
  enable_region_buffer         BooleanValue  default=true
    comment: "S9: Sequential .mca sector read-ahead when a region file is first accessed."

SECTION: mob_systems
  enable_ai_throttle           BooleanValue  default=true
    comment: "S4: Throttle mob AI tick frequency by distance from players and server TPS."
  enable_spawn_limiter         BooleanValue  default=true
    comment: "S5: Per-chunk TPS-scaled spawn attempt cooldown."
  enable_mob_normalizer        BooleanValue  default=true
    comment: "S10: Normalize modded spawn weight pools at server startup."
  mob_normalizer_max_weight    IntValue      default=80    range=[20, 200]
    comment: "S10: Maximum spawn weight any single entity may have after normalization."
  mob_normalizer_min_weight    IntValue      default=5     range=[1, 20]
    comment: "S10: Minimum spawn weight floor after normalization."
  mob_normalizer_exemptions    ConfigValue<List<String>>  default=[]
    comment: "S10: List of mod IDs to exempt from spawn weight normalization (e.g. [\"betteranimals\"])."
  enable_mob_cap_guard         BooleanValue  default=true
    comment: "S11: Per-category soft mob cap that scales down under server stress."
  enable_pathfinding_throttle  BooleanValue  default=true
    comment: "S12: Cap pathfinding node evaluations per tick based on mob distance and TPS."
```

### Client config keys and types

```
SECTION: client_systems
  enable_chunk_prioritizer     BooleanValue  default=true
    comment: "S13: Re-sort incoming chunk data packets nearest-first before rendering."
  enable_velocity_hint         BooleanValue  default=true
    comment: "S14: Send accurate client velocity vector to server for improved read-ahead."
  enable_entity_culler         BooleanValue  default=true
    comment: "S15: Skip rendering of occluded or distant entities."
  entity_cull_range            IntValue      default=64    range=[16, 256]
    comment: "S15: Entities beyond this distance in blocks are always culled from rendering."
```

### Static accessor fields

Expose every config value as a public static field so systems can read them without passing VCConfig around:

```java
// Example pattern (repeat for every key):
public static ForgeConfigSpec.BooleanValue ENABLE_TPS_TRACKING;
public static ForgeConfigSpec.BooleanValue ENABLE_GEN_THROTTLE;
public static ForgeConfigSpec.IntValue     NOISE_CACHE_SIZE;
// ... etc
public static ForgeConfigSpec.ConfigValue<List<? extends String>> MOB_NORMALIZER_EXEMPTIONS;
```

### Spec pair holders

```java
public static final ForgeConfigSpec SERVER_SPEC;
public static final ForgeConfigSpec CLIENT_SPEC;
```

Assign these at class init via a static initialiser block that constructs a `Builder`, calls all the push/define
methods in order, then calls `builder.build()`.

### Registration helper

```java
/**
 * Registers both server and client configs with the Forge config system.
 * Call this from VelocityCoreMod constructor before any other setup.
 *
 * @param modContainer the active ModContainer from FMLJavaModLoadingContext
 */
public static void register(ModContainer modContainer) { ... }
```

---

## VCNetworkChannel.java

**Package:** `com.velocitycore.network`

**Purpose:** Declares the single `SimpleChannel` used by the client companion to send `VelocityHint` packets
to the server (S14). The server never sends packets to clients in this version.

### Channel constants

```java
public static final ResourceLocation CHANNEL_NAME = new ResourceLocation("velocitycore", "network");
public static final String PROTOCOL_VERSION = "1";
```

### Channel field

```java
public static SimpleChannel INSTANCE;
```

### Packet IDs

```java
public static final int VELOCITY_HINT_ID = 0;
```

### Initialization

```java
/**
 * Creates and registers the SimpleChannel. Call this from VelocityCoreMod constructor.
 * Must be called on both Dist.CLIENT and Dist.DEDICATED_SERVER so both sides can decode.
 */
public static void init() { ... }
```

Use `NetworkRegistry.newSimpleChannel(...)` with `CHANNEL_NAME`, a supplier for `PROTOCOL_VERSION`,
`PROTOCOL_VERSION::equals` for client acceptor, and `PROTOCOL_VERSION::equals` for server acceptor.

### VelocityHintPacket (inner class or separate file — inner class preferred)

```java
/**
 * Compact packet sent by the client every tick when velocity changes by more than 0.1 blocks/tick.
 * Contains the player's actual velocity vector and movement type for server-side PreLoadRing (S6).
 *
 * Wire format: 3 floats (vx, vy, vz) + 1 byte (movementType ordinal) = 13 bytes per tick.
 */
public static class VelocityHintPacket {
    public final float vx, vy, vz;
    public final MovementType movementType;

    public enum MovementType { WALK, SPRINT, ELYTRA, HORSE, BOAT }

    public VelocityHintPacket(float vx, float vy, float vz, MovementType type) { ... }

    /** Encodes this packet into a FriendlyByteBuf for network transmission. */
    public void encode(FriendlyByteBuf buf) { ... }

    /** Decodes a packet from the network buffer. */
    public static VelocityHintPacket decode(FriendlyByteBuf buf) { ... }

    /**
     * Server-side handler. Looks up the player in the ServerGamePacketListenerImpl,
     * then calls PreLoadRing.receiveVelocityHint(player, vx, vy, vz, movementType).
     * Only invoked when VCConfig.ENABLE_PRELOAD_RING is true.
     */
    public void handle(Supplier<NetworkEvent.Context> ctx) { ... }
}
```

---

## Constraints

- Do not use `@Config` annotation — use `ModConfigSpec.Builder` manually for full control.
- `MOB_NORMALIZER_EXEMPTIONS` must be typed as `ForgeConfigSpec.ConfigValue<List<? extends String>>`.
- Both specs must be built before `VelocityCoreMod` constructor returns.
- `VCNetworkChannel.init()` must be called before any mod event subscribers fire.

## Files to create/edit

- `src/main/java/com/velocitycore/config/VCConfig.java`
- `src/main/java/com/velocitycore/network/VCNetworkChannel.java`
