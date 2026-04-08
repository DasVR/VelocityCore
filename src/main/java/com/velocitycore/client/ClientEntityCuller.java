package com.velocitycore.client;

import com.velocitycore.config.VCConfig;
import com.velocitycore.system.VCSystemMetrics;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * S15 — ClientEntityCuller
 *
 * Adds two culling passes to skip rendering of non-visible entities:
 *   1. Distance cull: entities beyond VCConfig.ENTITY_CULL_RANGE are always skipped
 *   2. Frustum cull: entity AABB tested against camera view frustum (requires LevelRenderer access)
 *   3. Occlusion cull: 3 raycasts from camera to entity bounding box corners
 *
 * Occlusion results are cached for 4 client ticks to limit rayCast cost.
 *
 * Works on any EntityType including modded mobs — uses standard AABB, no type checks.
 *
 * See .cursor/prompts/15_client_systems.md for full implementation brief.
 */
public final class ClientEntityCuller {

    /** Maps entity ID → [last-check-tick, isOccluded(0/1)]. Cached for OCCLUSION_CACHE_TICKS. */
    private static final Map<Integer, long[]> occlusionCache = new HashMap<>();
    private static long clientTick = 0L;
    private static final int OCCLUSION_CACHE_TICKS = 4;
    private static int lastCacheSize = 0;

    /**
     * Advances the client tick counter and prunes the occlusion cache.
     * Called by ClientTickHandler each client tick.
     *
     * @param mc the Minecraft instance
     */
    public static void tick(Minecraft mc) {
        clientTick++;
        if (clientTick % 60 == 0) pruneOcclusionCache();
        lastCacheSize = occlusionCache.size();
    }

    /**
     * Returns true if the given entity should be skipped for rendering.
     *
     * Tests (in order):
     *   1. Distance cull: entity distance > ENTITY_CULL_RANGE → skip
     *   2. Occlusion cull: all 3 rays from camera blocked by solid blocks → skip (cached 4 ticks)
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
        if (entity.distanceTo(camera.getEntity()) > cullRange) {
            VCSystemMetrics.increment("client.s15.distance_culled", 1L, clientTick);
            return true;
        }

        // 2. Occlusion cull (cached)
        long[] cached = occlusionCache.get(entity.getId());
        if (cached != null && (clientTick - cached[0]) < OCCLUSION_CACHE_TICKS) {
            return cached[1] == 1L;
        }

        boolean occluded = isOccluded(entity, camera, level);
        occlusionCache.put(entity.getId(), new long[]{clientTick, occluded ? 1L : 0L});
        if (occluded) {
            VCSystemMetrics.increment("client.s15.occlusion_culled", 1L, clientTick);
        }
        return occluded;
    }

    /**
     * Casts 3 rays from camera to entity AABB corners. Returns true if all 3 are blocked.
     * Uses level.clip() — the same mechanism Minecraft uses for line-of-sight tests.
     *
     * @param entity the entity to test
     * @param camera the current camera
     * @param level  the client level
     * @return true if all rays are occluded by solid blocks
     */
    private static boolean isOccluded(Entity entity, Camera camera, ClientLevel level) {
        AABB box = entity.getBoundingBox();
        Vec3 camPos = camera.getPosition();
        Vec3[] targets = {
            new Vec3(box.minX, box.minY, box.minZ),
            new Vec3(box.maxX, box.maxY, box.maxZ),
            new Vec3((box.minX + box.maxX) / 2.0, (box.minY + box.maxY) / 2.0,
                     (box.minZ + box.maxZ) / 2.0)
        };

        for (Vec3 target : targets) {
            ClipContext ctx = new ClipContext(camPos, target,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity);
            BlockHitResult hit = level.clip(ctx);
            if (hit.getType() != HitResult.Type.BLOCK) return false;
        }
        return true;
    }

    /** Removes stale occlusion cache entries. */
    private static void pruneOcclusionCache() {
        long cutoff = clientTick - (long)(OCCLUSION_CACHE_TICKS * 10);
        occlusionCache.entrySet().removeIf(e -> e.getValue()[0] < cutoff);
    }

    public static int getCacheSize() {
        return lastCacheSize;
    }

    private ClientEntityCuller() {}
}
