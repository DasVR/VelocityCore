# Prompt 06 — ModdedMobNormalizer (S10)

## What to implement

Implement `ModdedMobNormalizer.java` (S10). This system runs exactly once at server startup, after all mods
have completed registration, and rebalances every biome's mob spawn weight pools so modded mobs don't
drown out vanilla spawns (or each other).

---

## ModdedMobNormalizer.java

**Package:** `com.velocitycore.system`

**Purpose:** Iterates every registered biome's `MobSpawnSettings`, checks each `MobCategory` spawn list for
extreme weights (too high or too low relative to the vanilla baseline for that category), and applies a
normalization formula. Writes a detailed adjustment report to the server log. Respects a per-mod exemption
list from VCConfig.

### Vanilla pool baselines

These are approximate vanilla totals for a typical overworld biome (e.g. plains):

```java
private static final Map<MobCategory, Integer> VANILLA_BASELINES = Map.of(
    MobCategory.MONSTER,          615,
    MobCategory.CREATURE,          60,
    MobCategory.AMBIENT,           10,
    MobCategory.WATER_CREATURE,    10,
    MobCategory.WATER_AMBIENT,     25,
    MobCategory.MISC,               5
);
```

### Normalization formula

```
normalized = clamp(weight * (vanillaBaseline / actualTotal), MIN_WEIGHT, MAX_WEIGHT)

Where:
  weight       = the mob's current weight in the spawn list
  vanillaBaseline = VANILLA_BASELINES.getOrDefault(category, 100)
  actualTotal  = sum of all weights in this biome+category list
  MIN_WEIGHT   = VCConfig.MOB_NORMALIZER_MIN_WEIGHT.get()  (default 5)
  MAX_WEIGHT   = VCConfig.MOB_NORMALIZER_MAX_WEIGHT.get()  (default 80)
```

Only normalize if `actualTotal > vanillaBaseline * 1.5` (i.e. the pool is at least 50% larger than vanilla).
This avoids touching biomes whose pools are vanilla-sized.

### Entry point

```java
/**
 * Scans and normalizes all biome spawn pools.
 * Called from ServerTickHandler.onServerAboutToStart() after mod registration completes.
 *
 * Does nothing if VCConfig.ENABLE_MOB_NORMALIZER is false.
 *
 * @param server the MinecraftServer (used to access the biome registry)
 */
public static void normalize(MinecraftServer server) {
    if (!VCConfig.ENABLE_MOB_NORMALIZER.get()) return;
    // ...
}
```

### Implementation steps

1. Obtain `Registry<Biome>` from `server.registryAccess().registryOrThrow(Registries.BIOME)`.
2. For each biome in the registry:
   a. Get `biome.getMobSettings()`.
   b. For each `MobCategory` in `MobCategory.values()`:
      - Get the spawn list: `mobSettings.getMobs(category)`.
      - Sum the total weight of all entries.
      - If `totalWeight <= vanillaBaseline * 1.5`, skip (pool is not inflated).
      - Otherwise, apply normalization to each entry.
3. Apply weights using reflection since Forge does not expose a mutator on `MobSpawnSettings.SpawnerData`.
   - `MobSpawnSettings.SpawnerData` has field `weight` of type `int` — set it accessible and write.
4. Log a summary line per biome+category that was adjusted.
5. Log a grand total: X biomes adjusted, Y individual entries changed.

### Exemption check

```java
/**
 * Returns true if the given EntityType belongs to an exempted mod.
 * Checks the ResourceLocation namespace of the EntityType's registry key against VCConfig.MOB_NORMALIZER_EXEMPTIONS.
 *
 * @param type the EntityType to check
 * @return true if this type's mod is exempted
 */
private static boolean isExempted(EntityType<?> type) {
    String namespace = ForgeRegistries.ENTITY_TYPES.getKey(type).getNamespace();
    return VCConfig.MOB_NORMALIZER_EXEMPTIONS.get().contains(namespace);
}
```

Do not normalize entries where `isExempted(entry.type)` returns true. Log exempted entries as skipped.

### Logging format

Use `LogManager.getLogger("VelocityCore/MobNormalizer")` for all output.

```
[VelocityCore/MobNormalizer] Starting spawn weight normalization...
[VelocityCore/MobNormalizer] Biome: minecraft:plains | Category: MONSTER | Total weight: 1850 (baseline 615) — normalizing 12 entries
[VelocityCore/MobNormalizer]   minecraft:zombie        100 → 33
[VelocityCore/MobNormalizer]   someMod:custom_mob       80 → 26   (exempted: false)
[VelocityCore/MobNormalizer]   exemptedMod:their_mob   100 → SKIPPED (mod exempted)
[VelocityCore/MobNormalizer] Normalization complete: 147 biomes scanned, 89 adjusted, 412 entries modified.
```

### hasRun flag

```java
/** Set to true after normalize() completes. Prevents double-run on world reload. */
private static boolean hasRun = false;

/**
 * Returns whether normalization has already been performed this server session.
 *
 * @return true if normalize() has completed
 */
public static boolean hasRun() { return hasRun; }
```

---

## Constraints

- Must run exactly once per server session — guard with `hasRun` flag at top of `normalize()`.
- Reflection is required for `SpawnerData.weight` since Forge provides no public mutator.
- All reflection must be cached in a static `Field` variable — do not call `getDeclaredField` in a loop.
- Do not throw if reflection fails — log a warning and skip normalization for that entry gracefully.
- Exemptions use mod ID (namespace), not entity type name — `betteranimals` not `betteranimals:deer`.
- The normalization must NOT reduce any weight below `MIN_WEIGHT` or above `MAX_WEIGHT` — always clamp.
- Do NOT normalize entries from the `minecraft` namespace — only modded entries.

## Files to create/edit

- `src/main/java/com/velocitycore/system/ModdedMobNormalizer.java`
