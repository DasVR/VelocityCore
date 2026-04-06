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
        }

        // S7: evict gone-cold chunks if pruning interval has elapsed
        if (VCConfig.ENABLE_SMART_EVICTION.get()) {
            SmartEviction.pruneIfDue(gameTick);
            VCSystemMetrics.markSystemRun("S7_SmartEviction", gameTick);
            VCSystemMetrics.increment("eviction.hot_chunks", SmartEviction.getHotChunkCount(), gameTick);
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
        return sb.toString();
    }

    private static String buildVerboseStatusReport() {
        StringBuilder sb = new StringBuilder(buildStatusReport());
        sb.append("\n--- Verbose ---\n");
        sb.append("S3 last-run: ").append(VCSystemMetrics.getLastRunTick("S3_DeferredDecorator")).append("\n");
        sb.append("S4 last-run: ").append(VCSystemMetrics.getLastRunTick("S4_EntityActivation")).append("\n");
        sb.append("S5 last-run: ").append(VCSystemMetrics.getLastRunTick("S5_SpawnRateLimiter")).append("\n");
        sb.append("S6 last-run: ").append(VCSystemMetrics.getLastRunTick("S6_PreLoadRing")).append("\n");
        sb.append("S7 last-run: ").append(VCSystemMetrics.getLastRunTick("S7_SmartEviction")).append("\n");
        sb.append("Counters: ").append(VCSystemMetrics.countersReport());
        return sb.toString();
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
