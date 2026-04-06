package com.velocitycore.mixin;

import com.velocitycore.config.VCConfig;
import com.velocitycore.system.EntityActivationManager;
import com.velocitycore.system.MobCapGuard;
import com.velocitycore.system.SpawnRateLimiter;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin on net.minecraft.world.level.NaturalSpawner.
 *
 * Injections:
 *   1. vc_spawnRateLimitCheck — S5 SpawnRateLimiter: per-chunk cooldown before spawn attempt
 *   2. vc_mobCapGuardCheck    — S11 MobCapGuard: per-category soft cap before category spawn pass
 *
 * See .cursor/prompts/04_spawnratelimiter.md and 07_mobcapguard.md for full implementation brief.
 */
@Mixin(NaturalSpawner.class)
public abstract class MixinNaturalSpawner {

    // Injection 1: Spawn rate limit check (S5)
    // Rationale: HEAD with cancellable=true on spawnForChunk() allows short-circuiting the entire
    // spawn iteration for a chunk on cooldown, without touching any vanilla spawn logic.
    // Only natural spawning is affected — spawner blocks, eggs, and commands are not intercepted.
    @Inject(
        method = "spawnForChunk",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void vc_spawnRateLimitCheck(
            ServerChunkCache cache,
            ServerLevel level,
            LevelChunk chunk,
            NaturalSpawner.SpawnState spawnState,
            boolean spawnFriendlies,
            boolean spawnEnemies,
            boolean newChunk,
            CallbackInfo ci) {
        if (!VCConfig.ENABLE_SPAWN_LIMITER.get()) return;
        long tick = EntityActivationManager.getGameTick();
        if (SpawnRateLimiter.isCoolingDown(chunk.getPos(), tick)) {
            ci.cancel();
            return;
        }
        SpawnRateLimiter.markSpawned(chunk.getPos(), tick);
    }

    // Injection 2: MobCapGuard category check (S11)
    // Rationale: HEAD with cancellable=true on spawnCategoryForChunk() cancels the entire category
    // spawn pass before any iteration occurs. This is the earliest category-aware cancellation point.
    @Inject(
        method = "spawnCategoryForChunk",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void vc_mobCapGuardCheck(
            MobCategory category,
            ServerLevel level,
            LevelChunk chunk,
            NaturalSpawner.SpawnPredicate predicate,
            NaturalSpawner.AfterSpawnCallback callback,
            CallbackInfo ci) {
        if (!VCConfig.ENABLE_MOB_CAP_GUARD.get()) return;
        if (MobCapGuard.isCategoryOver(category, level)) {
            ci.cancel();
        }
    }
}
