package com.velocitycore.system;

import com.velocitycore.config.VCConfig;
import com.velocitycore.network.VCNetworkChannel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S6 — PreLoadRing
 *
 * Tracks each player's position delta across 5 ticks to derive a velocity vector, then
 * pre-issues UNKNOWN-type chunk load tickets for the likely next N chunks ahead of travel.
 *
 * Only issues tickets for chunks confirmed ChunkStatus.FULL on disk (Chunky-safe).
 * Falls back gracefully when chunks are not yet generated.
 * Client VelocityHint packet (S14) improves vector accuracy for elytra/boat/horse.
 *
 * Speed tiers:
 *   WALK=2, SPRINT=3, HORSE=4, ELYTRA=6, BOAT=3 read-ahead chunks
 *
 * See .cursor/prompts/09_preloadring.md and 11_preloadring.md for full implementation brief.
 */
public final class PreLoadRing {

    private static final Map<UUID, PlayerVelocityState> playerStates = new ConcurrentHashMap<>();

    private static class PlayerVelocityState {
        final Vec3[] posHistory = new Vec3[5];
        int head = 0;
        float vx = 0f, vy = 0f, vz = 0f;
        VCNetworkChannel.VelocityHintPacket.MovementType movementType =
            VCNetworkChannel.VelocityHintPacket.MovementType.WALK;
        long lastHintTick = -1;
    }

    /**
     * Per-tick update called by ServerTickHandler in Phase.END.
     *
     * @param server   the MinecraftServer
     * @param gameTick current game tick
     */
    public static void tick(MinecraftServer server, long gameTick) {
        if (!VCConfig.ENABLE_PRELOAD_RING.get()) return;
        if (!ChunkGenThrottle.hasBudget()) return;
        // TODO: implement — see prompts/09_preloadring.md and 11_preloadring.md
    }

    /**
     * Called by VCNetworkChannel when a VelocityHintPacket is received from a client.
     * Blends the client's precise velocity with the server's estimate (alpha=0.7 client).
     *
     * @param player       the server-side player entity
     * @param vx           client velocity x in blocks/tick
     * @param vy           client velocity y in blocks/tick
     * @param vz           client velocity z in blocks/tick
     * @param movementType client-reported movement type
     */
    public static void receiveVelocityHint(ServerPlayer player, float vx, float vy, float vz,
            VCNetworkChannel.VelocityHintPacket.MovementType movementType) {
        // TODO: implement velocity blending — see prompts/11_preloadring.md
    }

    /**
     * Removes state for a player who has left the server.
     *
     * @param playerId the UUID of the departing player
     */
    public static void removePlayer(UUID playerId) {
        playerStates.remove(playerId);
    }

    /**
     * Returns true if the chunk at (chunkX, chunkZ) is confirmed ChunkStatus.FULL on disk.
     * Caches results for 60 seconds. Returns false on any IO error (conservative).
     *
     * @param level  the ServerLevel
     * @param chunkX chunk X
     * @param chunkZ chunk Z
     * @return true if the chunk is FULL on disk
     */
    private static boolean isChunkFullOnDisk(ServerLevel level, int chunkX, int chunkZ) {
        // TODO: implement — see prompts/11_preloadring.md
        return false;
    }

    private PreLoadRing() {}
}
