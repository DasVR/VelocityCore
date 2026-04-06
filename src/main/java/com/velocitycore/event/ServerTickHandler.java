package com.velocitycore.event;

import com.mojang.brigadier.CommandDispatcher;
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
import net.minecraftforge.fml.util.thread.SidedThreadGroups;
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
        }

        // S5: prune stale cooldown entries
        if (VCConfig.ENABLE_SPAWN_LIMITER.get()) {
            SpawnRateLimiter.pruneTick(gameTick);
        }

        // S7: evict gone-cold chunks if pruning interval has elapsed
        if (VCConfig.ENABLE_SMART_EVICTION.get()) {
            SmartEviction.pruneIfDue(gameTick);
        }

        // S6: issue read-ahead tickets for predicted player positions
        if (VCConfig.ENABLE_PRELOAD_RING.get()) {
            PreLoadRing.tick(server, gameTick);
        }

        // S4: update game tick counter for EntityActivationManager
        if (VCConfig.ENABLE_AI_THROTTLE.get()) {
            EntityActivationManager.setGameTick(gameTick);
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
                    .executes(ctx -> {
                        ctx.getSource().sendSuccess(
                            () -> Component.literal(buildStatusReport()), false);
                        return 1;
                    }))
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

    private ServerTickHandler() {}
}
