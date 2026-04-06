package com.velocitycore;

import com.velocitycore.config.VCConfig;
import com.velocitycore.network.VCNetworkChannel;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * VelocityCore — Forge 1.20.1 server+client optimization mod.
 *
 * Entry point. Responsibilities:
 *   1. Register VCConfig (both server and client specs)
 *   2. Initialize VCNetworkChannel (SimpleChannel for S14 VelocityHint)
 *   3. Log startup confirmation with active system count
 *
 * Event handlers are registered via @Mod.EventBusSubscriber:
 *   - ServerTickHandler: Dist.DEDICATED_SERVER, Bus.FORGE
 *   - ClientTickHandler: Dist.CLIENT, Bus.FORGE
 *   (Both registered automatically at mod load — no explicit bus.register() needed)
 *
 * ModdedMobNormalizer (S10) is called from ServerTickHandler.onServerAboutToStart()
 * so it runs after all mod registrations are complete.
 */
@Mod("velocitycore")
public class VelocityCoreMod {

    public static final String MOD_ID = "velocitycore";
    private static final Logger LOGGER = LogManager.getLogger("VelocityCore");

    public VelocityCoreMod() {
        // Step 1: Register configs
        VCConfig.register(ModLoadingContext.get().getActiveContainer());

        // Step 2: Register network channel (both sides must register)
        VCNetworkChannel.init();

        // Step 3: Log startup
        // Active system count is computed post-config-load; log a placeholder here.
        // Full system count is available in /velocitycore status after server starts.
        LOGGER.info("=========================================");
        LOGGER.info("  VelocityCore initialized.");
        LOGGER.info("  15 systems registered. Server config: velocitycore-server.toml");
        LOGGER.info("  Run /velocitycore status in-game to see active system count.");
        LOGGER.info("  Chunky pre-generation required for S6/S7/S8/S9 full benefit.");
        LOGGER.info("=========================================");
    }
}
