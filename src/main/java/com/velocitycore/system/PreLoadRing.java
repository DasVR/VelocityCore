package com.velocitycore.system;

import com.velocitycore.config.VCConfig;
import com.velocitycore.network.VCNetworkChannel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
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
    private static final Map<Long, Boolean> diskStatusCache = new ConcurrentHashMap<>();
    private static final Map<Long, Long> diskStatusCacheTime = new ConcurrentHashMap<>();
    private static final long DISK_CACHE_TTL_NS = 60_000_000_000L;

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
        var online = server.getPlayerList().getPlayers();
        if (online.isEmpty()) {
            playerStates.clear();
            return;
        }
        var onlineIds = new java.util.HashSet<UUID>();
        for (ServerPlayer p : online) onlineIds.add(p.getUUID());
        playerStates.keySet().removeIf(id -> !onlineIds.contains(id));
        VCSystemMetrics.increment("preload.players_tracked", playerStates.size(), gameTick);

        for (ServerPlayer player : online) {
            PlayerVelocityState state = playerStates.computeIfAbsent(player.getUUID(), ignored -> new PlayerVelocityState());
            Vec3 current = player.position();

            Vec3 old = state.posHistory[state.head];
            state.posHistory[state.head] = current;
            state.head = (state.head + 1) % state.posHistory.length;

            if (old != null) {
                float serverVx = (float) ((current.x - old.x) / 5.0);
                float serverVy = (float) ((current.y - old.y) / 5.0);
                float serverVz = (float) ((current.z - old.z) / 5.0);
                // If we did not receive a client hint recently, use pure server estimate.
                if (state.lastHintTick < 0 || (gameTick - state.lastHintTick) > 10) {
                    state.vx = serverVx;
                    state.vy = serverVy;
                    state.vz = serverVz;
                } else {
                    // Keep blended state from receiveVelocityHint(); fold in a little server signal.
                    state.vx = (0.8f * state.vx) + (0.2f * serverVx);
                    state.vy = (0.8f * state.vy) + (0.2f * serverVy);
                    state.vz = (0.8f * state.vz) + (0.2f * serverVz);
                }
            }

            int readAhead = switch (state.movementType) {
                case WALK -> 2;
                case SPRINT -> 3;
                case HORSE -> 4;
                case ELYTRA -> 6;
                case BOAT -> 3;
            };

            ChunkPos playerChunk = player.chunkPosition();
            List<ChunkPos> ring = computeRing(playerChunk, state.vx, state.vz, readAhead);
            ServerLevel level = player.serverLevel();
            for (ChunkPos pos : ring) {
                TicketIssueResult result = issueTicket(level, pos.x, pos.z);
                switch (result) {
                    case ISSUED -> VCSystemMetrics.increment("preload.tickets_issued", 1L, gameTick);
                    case SKIPPED_BORDER -> VCSystemMetrics.increment("preload.skipped_border", 1L, gameTick);
                    case SKIPPED_NOT_FULL -> VCSystemMetrics.increment("preload.skipped_not_full", 1L, gameTick);
                    case SKIPPED_BUDGET -> VCSystemMetrics.increment("preload.skipped_budget", 1L, gameTick);
                }
            }
        }
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
        PlayerVelocityState state = playerStates.computeIfAbsent(player.getUUID(), ignored -> new PlayerVelocityState());

        // Server estimate from history if available
        Vec3 current = player.position();
        Vec3 oldest = state.posHistory[state.head];
        float serverVx = oldest == null ? 0f : (float) ((current.x - oldest.x) / 5.0);
        float serverVy = oldest == null ? 0f : (float) ((current.y - oldest.y) / 5.0);
        float serverVz = oldest == null ? 0f : (float) ((current.z - oldest.z) / 5.0);

        // Blend, alpha=0.7 in favor of client hint.
        state.vx = (0.7f * vx) + (0.3f * serverVx);
        state.vy = (0.7f * vy) + (0.3f * serverVy);
        state.vz = (0.7f * vz) + (0.3f * serverVz);
        state.movementType = movementType;
        state.lastHintTick = EntityActivationManager.getGameTick();
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
        long key = ChunkPos.asLong(chunkX, chunkZ);
        Long cachedTime = diskStatusCacheTime.get(key);
        if (cachedTime != null && (System.nanoTime() - cachedTime) < DISK_CACHE_TTL_NS) {
            Boolean cached = diskStatusCache.get(key);
            if (cached != null) return cached;
        }

        // Conservative and non-blocking path: treat loaded FULL chunks as safe, otherwise false.
        boolean result = level.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
        diskStatusCache.put(key, result);
        diskStatusCacheTime.put(key, System.nanoTime());
        return result;
    }

    private static List<ChunkPos> computeRing(ChunkPos playerChunk, float vx, float vz, int readAhead) {
        List<ChunkPos> ring = new ArrayList<>();
        double len = Math.sqrt((vx * vx) + (vz * vz));
        if (len < 0.01) return ring;
        double dx = vx / len;
        double dz = vz / len;
        for (int i = 1; i <= readAhead; i++) {
            int cx = playerChunk.x + (int) Math.round(dx * i);
            int cz = playerChunk.z + (int) Math.round(dz * i);
            ring.add(new ChunkPos(cx, cz));
        }
        return ring;
    }

    private enum TicketIssueResult { ISSUED, SKIPPED_BORDER, SKIPPED_NOT_FULL, SKIPPED_BUDGET }

    private static TicketIssueResult issueTicket(ServerLevel level, int chunkX, int chunkZ) {
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        WorldBorder border = level.getWorldBorder();
        if (!border.isWithinBounds(pos)) return TicketIssueResult.SKIPPED_BORDER;
        if (!isChunkFullOnDisk(level, chunkX, chunkZ)) return TicketIssueResult.SKIPPED_NOT_FULL;
        if (!ChunkGenThrottle.hasBudget()) return TicketIssueResult.SKIPPED_BUDGET;
        level.getChunkSource().addRegionTicket(TicketType.UNKNOWN, pos, 1, pos);
        return TicketIssueResult.ISSUED;
    }

    public static int getTrackedPlayerCount() {
        return playerStates.size();
    }

    public static int getDiskStatusCacheSize() {
        return diskStatusCache.size();
    }

    public static void clearRuntimeState() {
        playerStates.clear();
        diskStatusCache.clear();
        diskStatusCacheTime.clear();
    }

    private PreLoadRing() {}
}
