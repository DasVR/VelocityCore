package com.velocitycore.mixin;

import com.velocitycore.config.VCConfig;
import com.velocitycore.system.DeferredDecorator;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin on net.minecraft.world.level.chunk.ChunkGenerator.
 *
 * Injection:
 *   vc_deferDecoration — S3 DeferredDecorator: enqueue decoration instead of running inline
 */
@Mixin(ChunkGenerator.class)
public abstract class MixinChunkGenerator {

    // Injection point rationale: HEAD with cancellable=true on applyBiomeDecoration() replaces
    // the immediate decoration call with queued execution when enabled.
    @Inject(
        method = "applyBiomeDecoration",
        at = @At("HEAD"),
        cancellable = true
    )
    private void vc_deferDecoration(WorldGenLevel level, ChunkAccess chunk,
            StructureManager structureManager, CallbackInfo ci) {
        if (VCConfig.ENABLE_DEFERRED_DECORATOR.get()) {
            DeferredDecorator.enqueue(level, (ChunkGenerator) (Object) this, chunk.getPos());
            ci.cancel();
        }
    }
}
