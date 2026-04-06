package com.velocitycore.client;

import com.velocitycore.config.VCConfig;
import com.velocitycore.network.VCNetworkChannel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.phys.Vec3;

/**
 * S14 — VelocityHintSender
 *
 * Sends the client's actual velocity vector to the server each tick when it changes by more
 * than 0.1 blocks/tick. Allows server-side PreLoadRing (S6) to use precise movement data
 * instead of server-estimated position deltas, particularly for elytra/boat/horse movement.
 *
 * Packet: 3 floats (vx, vy, vz) + 1 byte (movement type) = 13 bytes per tick (when changed).
 *
 * Only sends when VCNetworkChannel is connected and the server channel is present.
 * Degrades gracefully when the server does not have VelocityCore installed.
 *
 * See .cursor/prompts/15_client_systems.md for full implementation brief.
 */
public final class VelocityHintSender {

    private static float lastVx = 0f, lastVy = 0f, lastVz = 0f;
    private static VCNetworkChannel.VelocityHintPacket.MovementType lastType =
        VCNetworkChannel.VelocityHintPacket.MovementType.WALK;

    private static final float CHANGE_THRESHOLD = 0.1f;

    /**
     * Sends a velocity hint packet if the player's velocity has changed beyond the threshold.
     * Called each client tick by ClientTickHandler.
     *
     * Movement type detection:
     *   ELYTRA: player is fall-flying
     *   HORSE:  player vehicle is AbstractHorse
     *   BOAT:   player vehicle is Boat
     *   SPRINT: player is sprinting
     *   WALK:   default
     *
     * @param player the local player
     */
    public static void tick(LocalPlayer player) {
        if (!VCConfig.ENABLE_VELOCITY_HINT.get()) return;
        if (VCNetworkChannel.INSTANCE == null) return;

        Vec3 vel = player.getDeltaMovement();
        float vx = (float) vel.x;
        float vy = (float) vel.y;
        float vz = (float) vel.z;
        VCNetworkChannel.VelocityHintPacket.MovementType type = detectMovementType(player);

        float dx = Math.abs(vx - lastVx);
        float dz = Math.abs(vz - lastVz);
        boolean changed = dx > CHANGE_THRESHOLD || dz > CHANGE_THRESHOLD || type != lastType;

        if (changed) {
            lastVx = vx;
            lastVy = vy;
            lastVz = vz;
            lastType = type;
            VCNetworkChannel.INSTANCE.sendToServer(
                new VCNetworkChannel.VelocityHintPacket(vx, vy, vz, type));
        }
    }

    /**
     * Detects the player's current movement type for the velocity hint packet.
     *
     * @param player the local player
     * @return the detected MovementType
     */
    private static VCNetworkChannel.VelocityHintPacket.MovementType detectMovementType(
            LocalPlayer player) {
        if (player.isFallFlying()) return VCNetworkChannel.VelocityHintPacket.MovementType.ELYTRA;
        if (player.getVehicle() instanceof AbstractHorse) return VCNetworkChannel.VelocityHintPacket.MovementType.HORSE;
        if (player.getVehicle() instanceof Boat) return VCNetworkChannel.VelocityHintPacket.MovementType.BOAT;
        if (player.isSprinting()) return VCNetworkChannel.VelocityHintPacket.MovementType.SPRINT;
        return VCNetworkChannel.VelocityHintPacket.MovementType.WALK;
    }

    private VelocityHintSender() {}
}
