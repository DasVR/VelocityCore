# Prompt 15 — Client Systems: ChunkPacketPrioritizer + VelocityHintSender + ClientEntityCuller (S13, S14, S15)

## What to implement

Implement all three client-side systems and `ClientTickHandler.java`. All client code runs under
`Dist.CLIENT` only. The client jar shares the same source tree — Forge handles the dist split at runtime.
These systems are optional: the server runs fully without them. Mixed servers (some players with companion,
some without) work correctly.

---

## ClientTickHandler.java

**Package:** `com.velocitycore.event`

```java
@Mod.EventBusSubscriber(modid = "velocitycore", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ClientTickHandler {

    /**
     * Client tick driver. Called every client game tick.
     *
     * Phase.END tasks (in order):
     *   1. ChunkPacketPrioritizer.drainTick()   — if ENABLE_CHUNK_PRIORITIZER
     *   2. VelocityHintSender.tick()            — if ENABLE_VELOCITY_HINT
     *   3. ClientEntityCuller.tick()            — if ENABLE_ENTITY_CULLER
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        if (VCConfig.ENABLE_CHUNK_PRIORITIZER.get()) {
            ChunkPacketPrioritizer.drainTick(mc);
        }
        if (VCConfig.ENABLE_VELOCITY_HINT.get()) {
            VelocityHintSender.tick(mc.player);
        }
        if (VCConfig.ENABLE_ENTITY_CULLER.get()) {
            ClientEntityCuller.tick(mc);
        }
    }
}
```

---

## ChunkPacketPrioritizer.java (S13)

**Package:** `com.velocitycore.client`

**Purpose:** Re-sorts incoming chunk data packets nearest-first before processing. Vanilla processes chunk
packets in registration order — distant chunks may render before the chunk the player is standing in. This
system intercepts chunk packets on the network thread and drains them nearest-first each render tick.

### Fields

```java
/**
 * Thread-safe priority queue keyed on squared distance from player's chunk.
 * Lower distance = higher priority (closer = drained first).
 *
 * The queue holds ClientboundLevelChunkWithLightPacket instances.
 * PriorityQueue is not thread-safe — use a ConcurrentLinkedQueue + sort approach instead.
 */
private static final ConcurrentLinkedQueue<PrioritizedChunkPacket> pending = new ConcurrentLinkedQueue<>();

/** Maximum packets drained per client tick to prevent frame stutter. */
private static final int DRAIN_PER_TICK = 8;

private static class PrioritizedChunkPacket {
    final ClientboundLevelChunkWithLightPacket packet;
    final int distanceSq; // (chunkX - playerChunkX)^2 + (chunkZ - playerChunkZ)^2

    PrioritizedChunkPacket(ClientboundLevelChunkWithLightPacket packet, int distanceSq) {
        this.packet = packet;
        this.distanceSq = distanceSq;
    }
}
```

### Packet interception

To intercept `ClientboundLevelChunkWithLightPacket` before vanilla processes it, use a Forge
`ClientPacketListener` event or a mixin on `ClientPacketListener.handleLevelChunkWithLight`. The recommended
approach for 1.20.1 is a `@Mixin` on `ClientPacketListener`:

```java
// In a separate MixinClientPacketListener.java:
// Injection point rationale: HEAD with cancellable=true on handleLevelChunkWithLight() intercepts
// the packet before vanilla queues any chunk loading work. We place it in our priority queue and
// cancel the vanilla handler, then drain from the queue nearest-first in ClientTickHandler.
@Inject(
    method = "handleLevelChunkWithLight",
    at = @At("HEAD"),
    cancellable = true
)
private void vc_interceptChunkPacket(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
    if (!VCConfig.ENABLE_CHUNK_PRIORITIZER.get()) return;
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null) { return; } // no player yet — don't intercept

    int playerCX = mc.player.chunkPosition().x;
    int playerCZ = mc.player.chunkPosition().z;
    int dx = packet.getX() - playerCX;
    int dz = packet.getZ() - playerCZ;
    int distSq = dx * dx + dz * dz;

    ChunkPacketPrioritizer.enqueue(packet, distSq);
    ci.cancel();
}
```

### Public API

```java
/**
 * Enqueues a chunk packet with its computed distance for later prioritized drain.
 * Called from the network thread — must be thread-safe.
 *
 * @param packet   the chunk packet to enqueue
 * @param distanceSq squared distance from player chunk
 */
public static void enqueue(ClientboundLevelChunkWithLightPacket packet, int distanceSq) {
    pending.offer(new PrioritizedChunkPacket(packet, distanceSq));
}

/**
 * Drains up to DRAIN_PER_TICK packets from the queue, sorted nearest-first.
 * Called on the client tick thread (main render thread) by ClientTickHandler.
 *
 * @param mc the Minecraft instance
 */
public static void drainTick(Minecraft mc) {
    if (pending.isEmpty()) return;

    // Collect all pending, sort by distanceSq ascending, drain up to DRAIN_PER_TICK
    List<PrioritizedChunkPacket> batch = new ArrayList<>();
    PrioritizedChunkPacket item;
    while ((item = pending.poll()) != null) batch.add(item);

    batch.sort(Comparator.comparingInt(p -> p.distanceSq));

    // Re-queue excess back (beyond DRAIN_PER_TICK)
    int drain = Math.min(batch.size(), DRAIN_PER_TICK);
    for (int i = drain; i < batch.size(); i++) pending.offer(batch.get(i));

    // Process the nearest DRAIN_PER_TICK packets
    ClientPacketListener connection = mc.getConnection();
    if (connection == null) return;
    for (int i = 0; i < drain; i++) {
        connection.handleLevelChunkWithLight(batch.get(i).packet);
    }
}
```

---

## VelocityHintSender.java (S14)

**Package:** `com.velocitycore.client`

**Purpose:** Sends the client's actual velocity vector to the server each tick when it changes by more than
0.1 blocks/tick. Allows server-side PreLoadRing (S6) to use precise movement data for elytra/boat/horse.

### Fields

```java
private static float lastVx = 0f, lastVy = 0f, lastVz = 0f;
private static VCNetworkChannel.VelocityHintPacket.MovementType lastType =
    VCNetworkChannel.VelocityHintPacket.MovementType.WALK;
private static final float CHANGE_THRESHOLD = 0.1f;
```

### Public API

```java
/**
 * Sends a velocity hint packet if the player's velocity has changed by more than the threshold.
 * Called each client tick by ClientTickHandler.
 *
 * Movement type detection:
 *   - Player is in elytra flight → ELYTRA
 *   - Player is on a horse/donkey/mule → HORSE
 *   - Player is in a boat → BOAT
 *   - Player is sprinting → SPRINT
 *   - Otherwise → WALK
 *
 * Only sends when VCNetworkChannel.INSTANCE is connected and the server channel is present.
 *
 * @param player the local player
 */
public static void tick(LocalPlayer player) {
    if (!VCConfig.ENABLE_VELOCITY_HINT.get()) return;
    if (VCNetworkChannel.INSTANCE == null) return;
    if (!VCNetworkChannel.INSTANCE.isChannelRegistered()) return;

    Vec3 vel = player.getDeltaMovement();
    float vx = (float) vel.x;
    float vy = (float) vel.y;
    float vz = (float) vel.z;
    VCNetworkChannel.VelocityHintPacket.MovementType type = detectMovementType(player);

    float dx = Math.abs(vx - lastVx);
    float dz = Math.abs(vz - lastVz);
    boolean changed = dx > CHANGE_THRESHOLD || dz > CHANGE_THRESHOLD || type != lastType;

    if (changed) {
        lastVx = vx; lastVy = vy; lastVz = vz; lastType = type;
        VCNetworkChannel.INSTANCE.sendToServer(
            new VCNetworkChannel.VelocityHintPacket(vx, vy, vz, type));
    }
}

private static VCNetworkChannel.VelocityHintPacket.MovementType detectMovementType(LocalPlayer player) {
    if (player.isFallFlying()) return VCNetworkChannel.VelocityHintPacket.MovementType.ELYTRA;
    if (player.getVehicle() instanceof AbstractHorse) return VCNetworkChannel.VelocityHintPacket.MovementType.HORSE;
    if (player.getVehicle() instanceof Boat) return VCNetworkChannel.VelocityHintPacket.MovementType.BOAT;
    if (player.isSprinting()) return VCNetworkChannel.VelocityHintPacket.MovementType.SPRINT;
    return VCNetworkChannel.VelocityHintPacket.MovementType.WALK;
}
```

---

## ClientEntityCuller.java (S15)

**Package:** `com.velocitycore.client`

**Purpose:** Adds two culling passes to skip rendering of non-visible entities: frustum culling (is the entity in
the camera's view cone?) and occlusion culling (is there solid terrain between camera and entity?). Occlusion
results are cached for 4 ticks.

### Fields

```java
/** Maps entity ID → (last-check-tick, isOccluded). Cached for 4 client ticks. */
private static final Map<Integer, long[]> occlusionCache = new HashMap<>(); // [tick, occluded(0/1)]
private static long clientTick = 0L;
private static final int OCCLUSION_CACHE_TICKS = 4;
private static final int RAYS_PER_ENTITY = 3; // cast to 3 corners of entity AABB
```

### Public API

```java
/**
 * Advances the client tick counter. Called by ClientTickHandler.
 * Also prunes the occlusion cache.
 *
 * @param mc the Minecraft instance
 */
public static void tick(Minecraft mc) {
    clientTick++;
    if (clientTick % 60 == 0) pruneOcclusionCache();
}

/**
 * Returns true if the given entity should be skipped for rendering.
 * Called from a mixin on EntityRenderDispatcher or LevelRenderer.
 *
 * Tests (in order):
 *   1. Distance cull: if entity distance > VCConfig.ENTITY_CULL_RANGE → skip
 *   2. Frustum cull: entity AABB vs camera frustum → skip if outside
 *   3. Occlusion cull: 3 raycasts from camera to entity AABB corners → skip if all occluded
 *      (result cached for OCCLUSION_CACHE_TICKS ticks)
 *
 * @param entity the entity being considered for rendering
 * @param camera the current rendering camera
 * @param level  the client level
 * @return true if the entity should be skipped
 */
public static boolean shouldCull(Entity entity, Camera camera, ClientLevel level) {
    if (!VCConfig.ENABLE_ENTITY_CULLER.get()) return false;

    // 1. Distance cull
    double cullRange = VCConfig.ENTITY_CULL_RANGE.get();
    if (entity.distanceTo(camera.getEntity()) > cullRange) return true;

    // 2. Frustum cull — delegate to existing Minecraft frustum if accessible
    // (LevelRenderer maintains a Frustum object that is updated each frame)
    // Skip if AABB is not in frustum — implementation depends on accessor to LevelRenderer.frustum

    // 3. Occlusion cull
    long[] cached = occlusionCache.get(entity.getId());
    if (cached != null && (clientTick - cached[0]) < OCCLUSION_CACHE_TICKS) {
        return cached[1] == 1L;
    }

    boolean occluded = isOccluded(entity, camera, level);
    occlusionCache.put(entity.getId(), new long[]{clientTick, occluded ? 1L : 0L});
    return occluded;
}

/**
 * Casts 3 rays from camera to entity AABB corners. Returns true if all 3 are blocked by solid blocks.
 *
 * Uses level.clip() (ClipContext) for each ray — the same mechanism Minecraft uses for line-of-sight.
 * Only tests BlockHitResult.Type.BLOCK (misses and entity hits do not count as occlusion).
 *
 * @param entity the entity to test
 * @param camera the current camera
 * @param level  the client level
 * @return true if all ray tests are occluded
 */
private static boolean isOccluded(Entity entity, Camera camera, ClientLevel level) {
    AABB box = entity.getBoundingBox();
    Vec3 camPos = camera.getPosition();
    Vec3[] targets = {
        new Vec3(box.minX, box.minY, box.minZ),
        new Vec3(box.maxX, box.maxY, box.maxZ),
        new Vec3((box.minX + box.maxX) / 2, (box.minY + box.maxY) / 2, (box.minZ + box.maxZ) / 2)
    };

    for (Vec3 target : targets) {
        ClipContext ctx = new ClipContext(camPos, target, ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, entity);
        BlockHitResult hit = level.clip(ctx);
        if (hit.getType() != HitResult.Type.BLOCK) return false; // ray not blocked → not fully occluded
    }
    return true; // all 3 rays blocked
}

private static void pruneOcclusionCache() {
    long cutoff = clientTick - OCCLUSION_CACHE_TICKS * 10L;
    occlusionCache.entrySet().removeIf(e -> e.getValue()[0] < cutoff);
}
```

---

## Constraints

**ChunkPacketPrioritizer:**
- `enqueue()` is called on the Netty network thread — must be thread-safe (ConcurrentLinkedQueue is fine).
- `drainTick()` is called on the main client thread — calling `connection.handleLevelChunkWithLight()` from
  here may not be thread-safe in all cases. Verify with Forge 1.20.1 threading model.
- If direct re-invocation of `handleLevelChunkWithLight` is not thread-safe, enqueue to the main thread
  via `Minecraft.getInstance().execute()` instead.

**VelocityHintSender:**
- Check `VCNetworkChannel.INSTANCE.isChannelRegistered()` before sending — server may not have VelocityCore.
- Only send when velocity changes exceed threshold — do not send every tick.
- Do not send if `player.isLocalPlayer()` is false (should not happen, but guard defensively).

**ClientEntityCuller:**
- Frustum access requires an `@Accessor` on `LevelRenderer` for the `frustum` field, or accessing it via
  the Minecraft rendering engine. Only implement frustum culling if the accessor is feasible.
- Occlusion raycasts are expensive — the 4-tick cache is mandatory, not optional.
- `isOccluded` must never be called from a render thread — it calls `level.clip()` which may not be render-safe.
  Ensure it is called from `tick()` (client tick thread), and results consumed from the render thread.

## Files to create/edit

- `src/main/java/com/velocitycore/event/ClientTickHandler.java`
- `src/main/java/com/velocitycore/client/ChunkPacketPrioritizer.java`
- `src/main/java/com/velocitycore/client/VelocityHintSender.java`
- `src/main/java/com/velocitycore/client/ClientEntityCuller.java`
