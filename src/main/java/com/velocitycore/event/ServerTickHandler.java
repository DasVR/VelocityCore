package com.velocitycore.event;

import com.mojang.brigadier.CommandDispatcher;
import com.velocitycore.VelocityCoreMod;
import com.velocitycore.config.VCConfig;
import com.velocitycore.system.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraftforge.event.TickEvent;

/**
 * Central event dispatcher for all server-side VelocityCore systems.
 * No system subscribes to tick events directly — all server-side tick work is driven from here.
 *
 * Registered on Dist.DEDICATED_SERVER to avoid loading on integrated client.
 *
 * Phase.START: ChunkGenThrottle.beginTick() — always runs first, resets budget.
 * Phase.END:   all system drain/tick calls, each guarded by its VCConfig boolean.
 *
 * See .cursor/prompts/02_chunkgenthrottle.md and 03_servertickhandler.md for full brief.
 */
@Mod.EventBusSubscriber(modid = "velocitycore", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.DEDICATED_SERVER)
public final class ServerTickHandler {

    /** Monotonically incrementing game tick counter. Starts at 0 on server start. */
    private static long gameTick = 0L;
    private static long lastTelemetryTick = 0L;

    /**
     * Main server tick driver. Called every game tick on the main server thread.
     *
     * @param event the ServerTickEvent from Forge
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            ChunkGenThrottle.beginTick();
            return;
        }

        // Phase.END
        gameTick++;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        // S3: drain deferred decoration tasks (main-thread safe block writes)
        if (VCConfig.ENABLE_DEFERRED_DECORATOR.get()) {
            for (ServerLevel level : server.getAllLevels()) {
                DeferredDecorator.drainTick(level);
            }
            VCSystemMetrics.markSystemRun("S3_DeferredDecorator", gameTick);
            VCSystemMetrics.increment("deferred.queue_depth", DeferredDecorator.getQueueDepth(), gameTick);
        }

        // S5: prune stale cooldown entries
        if (VCConfig.ENABLE_SPAWN_LIMITER.get()) {
            SpawnRateLimiter.pruneTick(gameTick);
            VCSystemMetrics.markSystemRun("S5_SpawnRateLimiter", gameTick);
            VCSystemMetrics.increment("spawn.cooldown_entries", SpawnRateLimiter.getTrackedChunkCount(), gameTick);
        }

        // S7: evict gone-cold chunks if pruning interval has elapsed
        if (VCConfig.ENABLE_SMART_EVICTION.get()) {
            SmartEviction.pruneIfDue(gameTick);
            VCSystemMetrics.markSystemRun("S7_SmartEviction", gameTick);
            VCSystemMetrics.increment("eviction.hot_chunks", SmartEviction.getHotChunkCount(), gameTick);
            VCSystemMetrics.increment("eviction.tracked_chunks", SmartEviction.getTrackedChunkCount(), gameTick);
        }

        // S6: issue read-ahead tickets for predicted player positions
        if (VCConfig.ENABLE_PRELOAD_RING.get()) {
            PreLoadRing.tick(server, gameTick);
            VCSystemMetrics.markSystemRun("S6_PreLoadRing", gameTick);
        }

        // S4: update game tick counter for EntityActivationManager
        if (VCConfig.ENABLE_AI_THROTTLE.get()) {
            EntityActivationManager.setGameTick(gameTick);
            VCSystemMetrics.markSystemRun("S4_EntityActivation", gameTick);
        }

        if (VCConfig.ENABLE_REGION_BUFFER.get()) {
            VCSystemMetrics.increment("region.warm_regions", RegionFileBuffer.getWarmRegionCount(), gameTick);
        }

        if ((gameTick - lastTelemetryTick) >= 1200L) {
            lastTelemetryTick = gameTick;
            if (VCSystemMetrics.isDebugEnabled()) {
                VelocityCoreMod.LOGGER.info("[VelocityCore] telemetry {}", buildTelemetryLine());
            }
        }
    }

    /**
     * Fires after all mods have completed registration, before the world loads.
     * Calls ModdedMobNormalizer.normalize() if ENABLE_MOB_NORMALIZER is true.
     *
     * @param event the ServerAboutToStartEvent from Forge
     */
    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        RuntimeSystemGate.runStartupCompatibilityReport();
        if (VCConfig.ENABLE_MOB_NORMALIZER.get()) {
            ModdedMobNormalizer.normalize(event.getServer());
        }
    }

    /**
     * Registers /velocitycore status and /velocitycore reload commands.
     *
     * @param event the RegisterCommandsEvent from Forge
     */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
            Commands.literal("velocitycore")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("status")
                    .then(Commands.literal("verbose")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(
                                () -> Component.literal(buildVerboseStatusReport()), false);
                            return 1;
                        }))
                    .executes(ctx -> {
                        ctx.getSource().sendSuccess(
                            () -> Component.literal(buildStatusReport()), false);
                        return 1;
                    }))
                .then(Commands.literal("counters")
                    .then(Commands.literal("reset")
                        .executes(ctx -> {
                            VCSystemMetrics.resetCounters(gameTick);
                            ctx.getSource().sendSuccess(
                                () -> Component.literal("[VelocityCore] Counters reset."), true);
                            return 1;
                        }))
                    .executes(ctx -> {
                        ctx.getSource().sendSuccess(
                            () -> Component.literal(VCSystemMetrics.countersReport()), false);
                        return 1;
                    }))
                .then(Commands.literal("debug")
                    .then(Commands.literal("on")
                        .executes(ctx -> {
                            VCSystemMetrics.setDebugEnabled(true);
                            ctx.getSource().sendSuccess(
                                () -> Component.literal("[VelocityCore] Debug telemetry enabled."), true);
                            return 1;
                        }))
                    .then(Commands.literal("off")
                        .executes(ctx -> {
                            VCSystemMetrics.setDebugEnabled(false);
                            ctx.getSource().sendSuccess(
                                () -> Component.literal("[VelocityCore] Debug telemetry disabled."), true);
                            return 1;
                        })))
                .then(Commands.literal("reload")
                    .executes(ctx -> {
                        ctx.getSource().sendSuccess(
                            () -> Component.literal("[VelocityCore] Config reloaded."), true);
                        return 1;
                    }))
        );
    }

    /**
     * Builds a multi-line status string for the /velocitycore status command.
     *
     * @return formatted status string
     */
    private static String buildStatusReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== VelocityCore Status ===\n");
        sb.append(ChunkGenThrottle.getStatusString()).append("\n");
        sb.append("Decoration queue: ").append(DeferredDecorator.getQueueDepth()).append(" tasks\n");
        sb.append("Hot chunks: ").append(SmartEviction.getHotChunkCount()).append("\n");
        sb.append(ChunkStatusFastPath.getStatusString()).append("\n");
        sb.append("MobNormalizer ran: ").append(ModdedMobNormalizer.hasRun()).append("\n");
        sb.append("Debug telemetry: ").append(VCSystemMetrics.isDebugEnabled()).append("\n");

        // Count active systems
        int active = 0;
        if (VCConfig.ENABLE_GEN_THROTTLE.get())         active++;
        if (VCConfig.ENABLE_NOISE_CACHE.get())          active++;
        if (VCConfig.ENABLE_DEFERRED_DECORATOR.get())   active++;
        if (VCConfig.ENABLE_AI_THROTTLE.get())          active++;
        if (VCConfig.ENABLE_SPAWN_LIMITER.get())        active++;
        if (VCConfig.ENABLE_PRELOAD_RING.get())         active++;
        if (VCConfig.ENABLE_SMART_EVICTION.get())       active++;
        if (VCConfig.ENABLE_FAST_PATH.get())            active++;
        if (VCConfig.ENABLE_REGION_BUFFER.get())        active++;
        if (VCConfig.ENABLE_MOB_NORMALIZER.get())       active++;
        if (VCConfig.ENABLE_MOB_CAP_GUARD.get())        active++;
        if (VCConfig.ENABLE_PATHFINDING_THROTTLE.get()) active++;

        sb.append("Active systems: ").append(active).append("/12 (server)");
        if (!RuntimeSystemGate.getDisabledSystems().isEmpty()) {
            sb.append("\nDegraded systems: ").append(RuntimeSystemGate.getDisabledSystems());
        }
        return sb.toString();
    }

    private static String buildVerboseStatusReport() {
        StringBuilder sb = new StringBuilder(buildStatusReport());
        sb.append("\n--- Verbose ---\n");
        sb.append(systemLine("S1 ChunkGenThrottle", VCConfig.ENABLE_GEN_THROTTLE.get(), true, "S1_ChunkGenThrottle")).append("\n");
        sb.append(systemLine("S2 SmartChunkCache", VCConfig.ENABLE_NOISE_CACHE.get(), true, "S2_SmartChunkCache")).append("\n");
        sb.append(systemLine("S3 DeferredDecorator", VCConfig.ENABLE_DEFERRED_DECORATOR.get(), true, "S3_DeferredDecorator")).append("\n");
        sb.append(systemLine("S4 EntityActivation", VCConfig.ENABLE_AI_THROTTLE.get(), true, "S4_EntityActivation")).append("\n");
        sb.append(systemLine("S5 SpawnRateLimiter", VCConfig.ENABLE_SPAWN_LIMITER.get(), true, "S5_SpawnRateLimiter")).append("\n");
        sb.append(systemLine("S6 PreLoadRing", VCConfig.ENABLE_PRELOAD_RING.get(), true, "S6_PreLoadRing")).append("\n");
        sb.append(systemLine("S7 SmartEviction", VCConfig.ENABLE_SMART_EVICTION.get(), true, "S7_SmartEviction")).append("\n");
        sb.append(systemLine("S8 ChunkStatusFastPath", VCConfig.ENABLE_FAST_PATH.get(), true, "S8_ChunkStatusFastPath")).append("\n");
        sb.append(systemLine("S9 RegionFileBuffer", VCConfig.ENABLE_REGION_BUFFER.get(), true, "S9_RegionFileBuffer")).append("\n");
        sb.append(systemLine("S10 ModdedMobNormalizer", VCConfig.ENABLE_MOB_NORMALIZER.get(), true, "S10_ModdedMobNormalizer")).append("\n");
        sb.append(systemLine("S11 MobCapGuard", VCConfig.ENABLE_MOB_CAP_GUARD.get(), true, "S11_MobCapGuard")).append("\n");
        sb.append(systemLine("S12 PathfindingThrottle", VCConfig.ENABLE_PATHFINDING_THROTTLE.get(),
            RuntimeSystemGate.isEnabled("S12_PATHFINDING", true), "S12_PathfindingThrottle")).append("\n");
        sb.append(systemLine("S13 ChunkPacketPrioritizer", VCConfig.ENABLE_CHUNK_PRIORITIZER.get(), true, "S13_ChunkPacketPrioritizer")).append("\n");
        sb.append(systemLine("S14 VelocityHintSender", VCConfig.ENABLE_VELOCITY_HINT.get(), true, "S14_VelocityHintSender")).append("\n");
        sb.append(systemLine("S15 ClientEntityCuller", VCConfig.ENABLE_ENTITY_CULLER.get(), true, "S15_ClientEntityCuller")).append("\n");
        sb.append("--- Signals ---\n");
        sb.append("S3 last-run: ").append(VCSystemMetrics.getLastRunTick("S3_DeferredDecorator")).append("\n");
        sb.append("S4 last-run: ").append(VCSystemMetrics.getLastRunTick("S4_EntityActivation")).append("\n");
        sb.append("S5 last-run: ").append(VCSystemMetrics.getLastRunTick("S5_SpawnRateLimiter")).append("\n");
        sb.append("S6 last-run: ").append(VCSystemMetrics.getLastRunTick("S6_PreLoadRing")).append("\n");
        sb.append("S7 last-run: ").append(VCSystemMetrics.getLastRunTick("S7_SmartEviction")).append("\n");
        sb.append("S13 last-run: ").append(VCSystemMetrics.getLastRunTick("S13_ChunkPacketPrioritizer")).append("\n");
        sb.append("S14 last-run: ").append(VCSystemMetrics.getLastRunTick("S14_VelocityHintSender")).append("\n");
        sb.append("S15 last-run: ").append(VCSystemMetrics.getLastRunTick("S15_ClientEntityCuller")).append("\n");
        sb.append("S5 cooldown entries: ").append(SpawnRateLimiter.getTrackedChunkCount()).append("\n");
        sb.append("S7 tracked chunks: ").append(SmartEviction.getTrackedChunkCount()).append("\n");
        sb.append("S7 decay tick: ").append(SmartEviction.getLastDecayTick()).append("\n");
        sb.append("S9 warmed regions: ").append(RegionFileBuffer.getWarmRegionCount()).append("\n");
        sb.append("Compatibility report: ").append(RuntimeSystemGate.getCompatibilityStatus()).append("\n");
        sb.append("Counters: ").append(VCSystemMetrics.countersReport());
        return sb.toString();
    }

    private static String systemLine(String name, boolean configEnabled, boolean runtimeEnabled, String metricSystemName) {
        long lastRun = VCSystemMetrics.getLastRunTick(metricSystemName);
        return name + " | config=" + configEnabled + " runtime=" + runtimeEnabled + " lastRun=" + lastRun;
    }

    private static String buildTelemetryLine() {
        return "tick=" + gameTick
            + " tps=" + String.format("%.2f", ChunkGenThrottle.getSmoothedTps())
            + " queue=" + DeferredDecorator.getQueueDepth()
            + " hot=" + SmartEviction.getHotChunkCount()
            + " counters=" + VCSystemMetrics.countersReport();
    }

    private ServerTickHandler() {}
}
