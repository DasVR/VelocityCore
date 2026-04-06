package com.velocitycore.network;

import com.velocitycore.system.PreLoadRing;
import com.velocitycore.config.VCConfig;
import com.velocitycore.system.EntityActivationManager;
import com.velocitycore.system.VCSystemMetrics;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * Declares the single SimpleChannel used by the client companion to send VelocityHint packets
 * to the server (S14). The server never sends packets to clients in this version.
 *
 * See .cursor/prompts/01_vcconfig.md for full implementation brief.
 */
public final class VCNetworkChannel {

    public static final ResourceLocation CHANNEL_NAME = new ResourceLocation("velocitycore", "network");
    public static final String PROTOCOL_VERSION = "1";

    /** The registered SimpleChannel instance. */
    public static SimpleChannel INSTANCE;

    public static final int VELOCITY_HINT_ID = 0;

    /**
     * Creates and registers the SimpleChannel.
     * Call this from VelocityCoreMod constructor on both Dist.CLIENT and Dist.DEDICATED_SERVER.
     */
    public static void init() {
        INSTANCE = NetworkRegistry.newSimpleChannel(
            CHANNEL_NAME,
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
        );

        INSTANCE.registerMessage(
            VELOCITY_HINT_ID,
            VelocityHintPacket.class,
            VelocityHintPacket::encode,
            VelocityHintPacket::decode,
            VelocityHintPacket::handle
        );
    }

    // -------------------------------------------------------------------------
    // VelocityHintPacket
    // -------------------------------------------------------------------------

    /**
     * Compact packet sent by the client every tick when velocity changes by more than 0.1 blocks/tick.
     * Contains the player's actual velocity vector and movement type for server-side PreLoadRing (S6).
     *
     * Wire format: 3 floats (vx, vy, vz) + 1 byte (movementType ordinal) = 13 bytes per tick.
     */
    public static class VelocityHintPacket {

        public final float vx, vy, vz;
        public final MovementType movementType;

        public enum MovementType { WALK, SPRINT, ELYTRA, HORSE, BOAT }

        public VelocityHintPacket(float vx, float vy, float vz, MovementType type) {
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.movementType = type;
        }

        /** Encodes this packet into a FriendlyByteBuf for network transmission. */
        public void encode(FriendlyByteBuf buf) {
            buf.writeFloat(vx);
            buf.writeFloat(vy);
            buf.writeFloat(vz);
            buf.writeByte(movementType.ordinal());
        }

        /** Decodes a packet from the network buffer. */
        public static VelocityHintPacket decode(FriendlyByteBuf buf) {
            float vx = buf.readFloat();
            float vy = buf.readFloat();
            float vz = buf.readFloat();
            int typeOrdinal = buf.readByte() & 0xFF;
            MovementType type = MovementType.values()[Math.min(typeOrdinal, MovementType.values().length - 1)];
            return new VelocityHintPacket(vx, vy, vz, type);
        }

        /**
         * Server-side handler. Looks up the sending player and calls
         * PreLoadRing.receiveVelocityHint() if ENABLE_PRELOAD_RING is true.
         */
        public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
            NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> {
                long tick = EntityActivationManager.getGameTick();
                VCSystemMetrics.increment("network.velocity_hint_received", 1L, tick);
                if (!VCConfig.ENABLE_PRELOAD_RING.get()) return;
                ServerPlayer player = ctx.getSender();
                if (player == null) return;
                PreLoadRing.receiveVelocityHint(player, vx, vy, vz, movementType);
                VCSystemMetrics.increment("network.velocity_hint_applied", 1L, tick);
            });
            ctx.setPacketHandled(true);
        }
    }

    private VCNetworkChannel() {}
}
