package com.velocitycore.mixin;

import com.velocitycore.system.SmartChunkCache;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * S2 SmartChunkCache — intercept {@link Climate.Sampler#sample} directly.
 *
 * <p>1.20.1 {@code NoiseBasedChunkGenerator#fillFromNoise} no longer contains an inlined
 * {@code Climate.Sampler.sample} call (terrain work is scheduled asynchronously), so a redirect
 * in {@code NoiseBasedChunkGenerator} fails mixin application with 0/1 targets.</p>
 */
@Mixin(Climate.Sampler.class)
public class MixinClimateSampler {

    private static final AtomicInteger AGENT_LOGS = new AtomicInteger(0);

    // #region agent log
    private static void agentLog(String hypothesisId, String location, String message, String dataJson) {
        if (AGENT_LOGS.get() >= 12) return;
        AGENT_LOGS.incrementAndGet();
        long ts = System.currentTimeMillis();
        String line = "{\"sessionId\":\"38a69b\",\"runId\":\"verify\",\"hypothesisId\":\"" + hypothesisId
            + "\",\"location\":\"" + location + "\",\"message\":\"" + message + "\",\"data\":" + dataJson
            + ",\"timestamp\":" + ts + "}\n";
        try {
            Path p = Path.of(System.getProperty("user.dir"), "debug-38a69b.log");
            Files.writeString(p, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }
    // #endregion

    @Inject(method = "sample", at = @At("HEAD"), cancellable = true)
    private void velocitycore$cachedHit(int x, int y, int z, CallbackInfoReturnable<Climate.TargetPoint> cir) {
        // #region agent log
        agentLog("H-verify", "MixinClimateSampler:HEAD", "sample_enter", "{\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + "}");
        // #endregion
        long key = SmartChunkCache.packKey(x, z, 0);
        Object cached = SmartChunkCache.get(key);
        if (cached instanceof Climate.TargetPoint point) {
            cir.setReturnValue(point);
            cir.cancel();
            // #region agent log
            agentLog("H-verify", "MixinClimateSampler:HEAD", "cache_hit", "{\"x\":" + x + ",\"z\":" + z + "}");
            // #endregion
        }
    }

    @Inject(method = "sample", at = @At("RETURN"))
    private void velocitycore$cachedPut(int x, int y, int z, CallbackInfoReturnable<Climate.TargetPoint> cir) {
        if (cir.isCancelled()) {
            return;
        }
        Climate.TargetPoint ret = cir.getReturnValue();
        if (ret != null) {
            SmartChunkCache.put(SmartChunkCache.packKey(x, z, 0), ret);
        }
    }
}
