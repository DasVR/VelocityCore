package com.velocitycore.client;

import com.velocitycore.config.VCConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * S13 — ChunkPacketPrioritizer
 *
 * Re-sorts incoming chunk data packets nearest-first before processing. Intercepts
 * ClientboundLevelChunkWithLightPacket on the network thread, places them in a priority
 * queue sorted by distance to the player, and drains them nearest-first each client tick.
 *
 * Most impactful on server join and /tp — eliminates the 'floating in void' stutter.
 * Drains up to 8 packets per tick.
 *
 * Requires a mixin on ClientPacketListener to intercept packets — see prompt 15.
 *
 * See .cursor/prompts/15_client_systems.md for full implementation brief.
 */
public final class ChunkPacketPrioritizer {

    /** Thread-safe queue. Enqueue on network thread; drain on client tick thread. */
    private static final ConcurrentLinkedQueue<PrioritizedChunkPacket> pending =
        new ConcurrentLinkedQueue<>();

    private static final int DRAIN_PER_TICK = 8;

    private static class PrioritizedChunkPacket {
        final ClientboundLevelChunkWithLightPacket packet;
        final int distanceSq;

        PrioritizedChunkPacket(ClientboundLevelChunkWithLightPacket packet, int distanceSq) {
            this.packet = packet;
            this.distanceSq = distanceSq;
        }
    }

    /**
     * Enqueues a chunk packet with its computed distance for later prioritized drain.
     * Called from the network thread — ConcurrentLinkedQueue ensures thread safety.
     *
     * @param packet     the chunk packet to enqueue
     * @param distanceSq squared distance from player chunk
     */
    public static void enqueue(ClientboundLevelChunkWithLightPacket packet, int distanceSq) {
        pending.offer(new PrioritizedChunkPacket(packet, distanceSq));
    }

    /**
     * Drains up to DRAIN_PER_TICK packets from the queue, sorted nearest-first.
     * Called on the client tick thread by ClientTickHandler.
     *
     * @param mc the Minecraft instance
     */
    public static void drainTick(Minecraft mc) {
        if (!VCConfig.ENABLE_CHUNK_PRIORITIZER.get() || pending.isEmpty()) return;

        List<PrioritizedChunkPacket> batch = new ArrayList<>();
        PrioritizedChunkPacket item;
        while ((item = pending.poll()) != null) batch.add(item);

        batch.sort(Comparator.comparingInt(p -> p.distanceSq));

        int drain = Math.min(batch.size(), DRAIN_PER_TICK);
        for (int i = drain; i < batch.size(); i++) pending.offer(batch.get(i));

        ClientPacketListener connection = mc.getConnection();
        if (connection == null) return;
        for (int i = 0; i < drain; i++) {
            connection.handleLevelChunkWithLight(batch.get(i).packet);
        }
    }

    private ChunkPacketPrioritizer() {}
}
