package com.velocitycore.event;

import com.velocitycore.client.ChunkPacketPrioritizer;
import com.velocitycore.client.ClientEntityCuller;
import com.velocitycore.client.VelocityHintSender;
import com.velocitycore.config.VCConfig;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Central event dispatcher for all client-side VelocityCore systems (S13, S14, S15).
 * Registered on Dist.CLIENT only — never loads on dedicated server.
 *
 * Phase.END drives:
 *   S13 ChunkPacketPrioritizer.drainTick()
 *   S14 VelocityHintSender.tick()
 *   S15 ClientEntityCuller.tick()
 *
 * See .cursor/prompts/15_client_systems.md for full implementation brief.
 */
@Mod.EventBusSubscriber(modid = "velocitycore", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientTickHandler {

    /**
     * Client tick driver. Called every client game tick.
     *
     * @param event the ClientTickEvent from Forge
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        if (VCConfig.ENABLE_CHUNK_PRIORITIZER.get()) {
            ChunkPacketPrioritizer.drainTick(mc);
        }
        if (VCConfig.ENABLE_VELOCITY_HINT.get()) {
            VelocityHintSender.tick(mc.player);
        }
        if (VCConfig.ENABLE_ENTITY_CULLER.get()) {
            ClientEntityCuller.tick(mc);
        }
    }

    private ClientTickHandler() {}
}
