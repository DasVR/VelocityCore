package com.velocitycore.mixin;

import com.velocitycore.config.VCConfig;
import com.velocitycore.system.ChunkStatusFastPath;
import com.velocitycore.system.RegionFileBuffer;
import com.velocitycore.system.RuntimeSystemGate;
import com.velocitycore.system.SmartEviction;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin on net.minecraft.server.level.ServerChunkCache.
 *
 * Injections:
 *   1. vc_fastPathCheck — S8 ChunkStatusFastPath: skip pipeline for FULL chunks already in memory
 *   2. vc_recordAccess  — S7 SmartEviction: record chunk access for LFU tracking
 *   3. vc_regionWarm    — S9 RegionFileBuffer: trigger background sector warming on chunk load
 *
 * See .cursor/prompts/12_smarteviction.md and 14_regionfilebuffer.md for full implementation brief.
 */
@Mixin(ServerChunkCache.class)
public abstract class MixinServerChunkCache {

    // Injection 1: Fast path for FULL chunks (S8)
    // Rationale: HEAD of getChunk() is the earliest point to intercept a chunk retrieval.
    // For FULL chunks already in memory, we skip all pipeline checks and return immediately.
    @Inject(
        method = "getChunk(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void vc_fastPathCheck(int chunkX, int chunkZ, ChunkStatus requiredStatus, boolean load,
            CallbackInfoReturnable<ChunkAccess> cir) {
        if (!RuntimeSystemGate.isEnabled("S8_FAST_PATH", VCConfig.ENABLE_FAST_PATH.get())) return;
        ChunkAccess fast = ChunkStatusFastPath.tryFastLoad(
            (ServerChunkCache)(Object)this, chunkX, chunkZ, requiredStatus);
        if (fast != null) cir.setReturnValue(fast);
    }

    // Injection 2: Record chunk access for SmartEviction (S7)
    // Rationale: RETURN on getChunkNow() captures every successful in-memory chunk lookup,
    // providing the access frequency signal for LFU tracking.
    @Inject(
        method = "getChunkNow",
        at = @At("RETURN")
    )
    private void vc_recordAccess(int chunkX, int chunkZ, CallbackInfoReturnable<LevelChunk> cir) {
        if (!RuntimeSystemGate.isEnabled("S7_SMART_EVICTION", VCConfig.ENABLE_SMART_EVICTION.get())) return;
        if (cir.getReturnValue() != null) {
            SmartEviction.recordAccess(new ChunkPos(chunkX, chunkZ));
        }
    }

    // Injection 3: Region file warming for RegionFileBuffer (S9)
    // Rationale: RETURN on getChunk() fires after a chunk has been loaded from disk, making it
    // the right trigger for speculative warming of adjacent sectors on the background I/O thread.
    @Inject(
        method = "getChunk(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At("RETURN")
    )
    private void vc_regionWarm(int chunkX, int chunkZ, ChunkStatus requiredStatus, boolean load,
            CallbackInfoReturnable<ChunkAccess> cir) {
        if (!RuntimeSystemGate.isEnabled("S9_REGION_BUFFER", VCConfig.ENABLE_REGION_BUFFER.get())) return;
        if (!load || cir.getReturnValue() == null) return;
        if (((ServerChunkCache)(Object)this).getLevel() instanceof ServerLevel level) {
            RegionFileBuffer.onChunkRead(level, new ChunkPos(chunkX, chunkZ));
        }
    }
}
