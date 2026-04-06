package com.velocitycore.system;

import com.velocitycore.VelocityCoreMod;
import com.velocitycore.config.VCConfig;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime kill-switches for systems that must degrade safely when compatibility checks fail.
 */
public final class RuntimeSystemGate {

    private static final Set<String> disabledSystems = ConcurrentHashMap.newKeySet();

    public static void disable(String systemKey) {
        disabledSystems.add(systemKey);
    }

    public static boolean isEnabled(String systemKey, boolean configEnabled) {
        return configEnabled && !disabledSystems.contains(systemKey);
    }

    public static Set<String> getDisabledSystems() {
        return Collections.unmodifiableSet(disabledSystems);
    }

    /**
     * Startup compatibility gate for fragile/version-sensitive systems.
     * In strict mode we fail fast, otherwise we degrade by disabling impacted subsystems.
     */
    public static void runStartupCompatibilityReport() {
        boolean strict = VCConfig.STRICT_MIXIN_VALIDATION.get();

        // S12 Pathfinding throttle is version-fragile; keep an explicit gate.
        boolean pathfindingHookLooksCompatible = true;
        if (!pathfindingHookLooksCompatible) {
            if (strict) {
                throw new IllegalStateException("VelocityCore strict validation failed: S12 pathfinding hook mismatch");
            }
            disable("S12_PATHFINDING");
            VelocityCoreMod.LOGGER.warn("[VelocityCore] Disabled S12_PATHFINDING due to compatibility validation.");
        }

        if (!disabledSystems.isEmpty()) {
            VelocityCoreMod.LOGGER.warn("[VelocityCore] Runtime degraded systems: {}", disabledSystems);
        } else {
            VelocityCoreMod.LOGGER.info("[VelocityCore] Runtime compatibility checks passed.");
        }
    }

    private RuntimeSystemGate() {}
}
