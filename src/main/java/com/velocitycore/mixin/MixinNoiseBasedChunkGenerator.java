package com.velocitycore.mixin;

import com.velocitycore.config.VCConfig;
import com.velocitycore.system.DeferredDecorator;
import com.velocitycore.system.SmartChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.WorldGenLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin on net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator.
 *
 * Injections:
 *   1. vc_deferDecoration — S3 DeferredDecorator: enqueue decoration instead of running inline
 *   2. vc_cachedSample    — S2 SmartChunkCache: intercept noise/biome sampling for caching
 *
 * See .cursor/prompts/08_deferreddecorator.md and 10_smartchunkcache.md for full implementation brief.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class MixinNoiseBasedChunkGenerator {

    // Injection 1: Defer decoration (S3)
    // Rationale: HEAD with cancellable=true on applyBiomeDecoration() replaces the entire
    // decoration call with a queue enqueue. This is safe because decoration is idempotent per-chunk.
    // When ENABLE_DEFERRED_DECORATOR is false, enqueue() handles the inline fallback and we
    // cancel here to avoid running the work twice.
    @Inject(
        method = "applyBiomeDecoration",
        at = @At("HEAD"),
        cancellable = true
    )
    private void vc_deferDecoration(WorldGenLevel level, ChunkAccess chunk,
            StructureManager structureManager, CallbackInfo ci) {
        if (VCConfig.ENABLE_DEFERRED_DECORATOR.get()) {
            DeferredDecorator.enqueue(level, (ChunkGenerator)(Object)this, chunk.getPos());
            ci.cancel();
        }
        // When disabled: fall through to vanilla applyBiomeDecoration
    }

    // Injection 2: Noise sample cache (S2)
    // Rationale: Climate.Sampler.sample() calls are intercepted to check SmartChunkCache first.
    // The exact @Redirect or @Inject target depends on 1.20.1 decompilation results.
    // See prompts/10_smartchunkcache.md — implement using Approach A (@Redirect) or B (@Inject)
    // after verifying the INVOKE target in the decompiled Forge 1.20.1 sources.
    // TODO: implement noise sample cache injection — see prompts/10_smartchunkcache.md
}
