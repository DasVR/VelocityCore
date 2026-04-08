# VelocityCore

VelocityCore is a **Forge 1.20.1 performance optimization mod** focused on high-load survival servers (especially worldgen-heavy setups such as **WWEE + Chunky**).  
It combines server-side scheduling and throttling with optional client-side companion systems to reduce lag spikes, smooth chunk traversal, and keep gameplay stable under stress.

---

## Design Goals

- **Server-first**: all critical gains work on dedicated servers even without client installs.
- **Graceful degradation**: fragile/version-sensitive hook paths can be disabled at runtime instead of crashing production.
- **Operator observability**: built-in command diagnostics, counters, and compatibility matrix for live triage.
- **Forge 1.20.1 alignment**: implemented against Forge `47.3.0` with runtime validation gates.

---

## Feature Systems (S1–S15)

VelocityCore implements 15 systems:

### Server systems (core)

- **S1 `ChunkGenThrottle`**: shared generation budget + TPS smoothing.
- **S2 `SmartChunkCache`**: thread-local sample caching for expensive lookups.
- **S3 `DeferredDecorator`**: queues expensive decoration work and drains with budget control.
- **S4 `EntityActivationManager`**: distance/TPS-aware mob ticking throttling.
- **S5 `SpawnRateLimiter`**: per-chunk cooldown to reduce spawn storm bursts.
- **S6 `PreLoadRing`**: velocity-based predictive ticketing ahead of player movement.
- **S7 `SmartEviction`**: LFU-style chunk hotness tracking and decay.
- **S8 `ChunkStatusFastPath`**: fast in-memory path for `ChunkStatus.FULL` lookups.
- **S9 `RegionFileBuffer`**: background read-only warming of region sectors.
- **S10 `ModdedMobNormalizer`**: startup normalization of inflated spawn weight pools.
- **S11 `MobCapGuard`**: stress-aware soft cap protection per mob category.
- **S12 `PathfindingThrottle`**: per-call node-budget control for AI pathfinding.

### Client companion systems (optional)

- **S13 `ChunkPacketPrioritizer`**: nearest-first chunk packet handling.
- **S14 `VelocityHintSender`**: sends movement vectors to improve server preload predictions.
- **S15 `ClientEntityCuller`**: distance/occlusion culling on client render path.

---

## Compatibility Model

VelocityCore is built to be safe in real production modpacks:

- **Strict mode (dev/staging)**: fail fast if compatibility checks fail.
- **Degrade mode (production default)**: disable incompatible subsystems while keeping server online.

Compatibility state is visible via command output:

- `/velocitycore status`
- `/velocitycore status verbose` (includes compatibility matrix + degraded system list)

---

## Installation

### Server install

1. Build or obtain `velocitycore-<version>.jar`.
2. Place it in server `mods/`.
3. Start server once to generate config:
   - `config/velocitycore-server.toml`

### Optional client install

Install the same mod jar on clients to enable S13/S14/S15 features.  
Server remains functional without client install.

---

## Configuration

Primary server config file:

- `config/velocitycore-server.toml`

Primary client config file:

- `config/velocitycore-client.toml`

### Key server toggles

- `general.enable_tps_tracking`
- `general.strict_mixin_validation`

- `chunk_systems.enable_gen_throttle`
- `chunk_systems.enable_noise_cache`
- `chunk_systems.enable_deferred_decorator`
- `chunk_systems.enable_preload_ring`
- `chunk_systems.enable_smart_eviction`
- `chunk_systems.enable_fast_path`
- `chunk_systems.enable_region_buffer`

- `mob_systems.enable_ai_throttle`
- `mob_systems.enable_spawn_limiter`
- `mob_systems.enable_mob_normalizer`
- `mob_systems.enable_mob_cap_guard`
- `mob_systems.enable_pathfinding_throttle`

### Key client toggles

- `client_systems.enable_chunk_prioritizer`
- `client_systems.enable_velocity_hint`
- `client_systems.enable_entity_culler`

---

## Commands & Observability

VelocityCore provides operational commands under `/velocitycore`:

- `/velocitycore status`  
  High-level health snapshot.

- `/velocitycore status verbose`  
  Full S1–S15 system matrix with:
  - config enable state
  - runtime enabled/degraded status
  - last-run ticks (where instrumented)
  - counters and pressure signals
  - compatibility report + validation matrix

- `/velocitycore counters`  
  Runtime counters (minute + total).

- `/velocitycore counters reset`  
  Reset runtime counters.

- `/velocitycore debug on|off`  
  Toggle periodic structured telemetry logging.

---

## Runtime Safety & Lifecycle Behavior

- Runtime metrics and preload caches are reset on server lifecycle boundaries.
- Preload ring prunes stale player state automatically.
- Compatibility checks execute during startup before heavy runtime execution.
- Non-core hook failures can be degraded by disabling impacted subsystem instead of crashing (unless strict mode is enabled).

---

## Build from Source

### Requirements

- Linux/macOS/Windows
- Java **17** JDK
- Gradle wrapper (included)

### Build commands

```bash
./gradlew compileJava
./gradlew build
```

Generated jar will be under `build/libs/`.

---

## Recommended Validation Flow

For production readiness, validate in this sequence:

1. **Compile/build checks**
   - `./gradlew compileJava`
   - `./gradlew build`
2. **Server startup smoke**
   - world loads cleanly
   - no unresolved critical mixin failures
3. **Command verification**
   - `/velocitycore status`
   - `/velocitycore status verbose`
   - `/velocitycore counters`
4. **Stress behavior**
   - worldgen traversal
   - spawn-heavy zones
   - long traversal paths
5. **Client matrix**
   - server-only
   - client+server installed
   - mixed population

---

## Troubleshooting

- **Server won’t start in strict mode**
  - Set `general.strict_mixin_validation=false` to allow degrade mode.
  - Check `/velocitycore status verbose` for disabled systems and validation failures.

- **No client features observed**
  - Ensure client has mod installed.
  - Verify client config toggles for S13/S14/S15.

- **Preload behavior seems inactive**
  - Confirm `enable_preload_ring=true`.
  - Ensure movement is significant enough to trigger predictive ring.
  - Check verbose counters for preload tickets/skip reasons.

- **Unexpected performance behavior**
  - Use `/velocitycore counters` and verbose status to identify active pressure points (queue depth, hot chunk pressure, skipped budget counters, etc.).

---

## Notes

- VelocityCore is tuned first for chunk-generation-heavy workloads.
- Client systems are additive; server systems remain authoritative.
- Runtime compatibility gate is designed to protect uptime in diverse modpacks.