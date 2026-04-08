package com.velocitycore.system;

import com.velocitycore.VelocityCoreMod;
import com.velocitycore.config.VCConfig;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.pathfinder.BinaryHeap;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathFinder;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime kill-switches for systems that must degrade safely when compatibility checks fail.
 */
public final class RuntimeSystemGate {

    private static final Set<String> disabledSystems = ConcurrentHashMap.newKeySet();
    private static final Map<String, Boolean> lastValidationResults = new ConcurrentHashMap<>();
    private static volatile String lastCompatibilityReport = "not-run";

    public static void disable(String systemKey) {
        disabledSystems.add(systemKey);
    }

    public static boolean isEnabled(String systemKey, boolean configEnabled) {
        return configEnabled && !disabledSystems.contains(systemKey);
    }

    public static Set<String> getDisabledSystems() {
        return Collections.unmodifiableSet(disabledSystems);
    }

    public static String getCompatibilityReport() {
        return lastCompatibilityReport;
    }

    public static String getCompatibilityStatus() {
        return getCompatibilityReport();
    }

    public static String getCompatibilityMatrixReport() {
        return getValidationMatrixString();
    }

    public static Map<String, Boolean> getValidationResultsSnapshot() {
        return new LinkedHashMap<>(lastValidationResults);
    }

    public static String getValidationMatrixString() {
        Map<String, Boolean> snapshot = getValidationResultsSnapshot();
        if (snapshot.isEmpty()) return "not-run";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Boolean> e : snapshot.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(e.getKey()).append("=").append(e.getValue() ? "PASS" : "FAIL");
        }
        return sb.toString();
    }

    /**
     * Startup compatibility gate for fragile/version-sensitive systems.
     * In strict mode we fail fast, otherwise we degrade by disabling impacted subsystems.
     */
    public static void runStartupCompatibilityReport() {
        boolean strict = VCConfig.STRICT_MIXIN_VALIDATION.get();
        disabledSystems.clear();
        lastValidationResults.clear();

        validateOrDisable("S12_PATHFINDING", strict, validatePathfindingHooks(), "pathfinding hook mismatch");
        validateOrDisable("S3_DEFERRED_DECORATOR", strict, validateDeferredDecoratorHooks(), "deferred decorator hook mismatch");
        validateOrDisable("S8_FAST_PATH", strict, validateFastPathHooks(), "fast-path hook mismatch");
        validateOrDisable("S7_SMART_EVICTION", strict, validateFastPathHooks(), "smart-eviction hook mismatch");
        validateOrDisable("S9_REGION_BUFFER", strict, validateFastPathHooks(), "region-buffer hook mismatch");

        lastCompatibilityReport = "strict=" + strict + " disabled=" + disabledSystems;
        if (!disabledSystems.isEmpty()) {
            VelocityCoreMod.LOGGER.warn("[VelocityCore] Runtime degraded systems: {}", disabledSystems);
        } else {
            VelocityCoreMod.LOGGER.info("[VelocityCore] Runtime compatibility checks passed.");
        }
    }

    private static boolean validatePathfindingHooks() {
        try {
            Method findPath = null;
            for (Method m : PathFinder.class.getDeclaredMethods()) {
                if ("findPath".equals(m.getName())) {
                    findPath = m;
                    break;
                }
            }
            if (findPath == null) return false;
            Method pop = BinaryHeap.class.getDeclaredMethod("pop");
            return Node.class.isAssignableFrom(pop.getReturnType());
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static boolean validateDeferredDecoratorHooks() {
        try {
            Method apply = ChunkGenerator.class.getDeclaredMethod(
                "applyBiomeDecoration",
                net.minecraft.world.level.WorldGenLevel.class,
                net.minecraft.world.level.chunk.ChunkAccess.class,
                net.minecraft.world.level.StructureManager.class
            );
            return apply != null;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static boolean validateFastPathHooks() {
        try {
            Method getChunk = ServerChunkCache.class.getDeclaredMethod(
                "getChunk",
                int.class, int.class, net.minecraft.world.level.chunk.ChunkStatus.class, boolean.class
            );
            Method getChunkNow = ServerChunkCache.class.getDeclaredMethod("getChunkNow", int.class, int.class);
            return getChunk != null && getChunkNow != null;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static void validateOrDisable(String systemKey, boolean strict, boolean valid, String reason) {
        lastValidationResults.put(systemKey, valid);
        if (valid) return;
        if (strict) {
            throw new IllegalStateException("VelocityCore strict validation failed: " + systemKey + " " + reason);
        }
        disable(systemKey);
        VelocityCoreMod.LOGGER.warn("[VelocityCore] Disabled {} due to compatibility validation: {}", systemKey, reason);
    }

    private RuntimeSystemGate() {}
}
