# Prompt 10 — SmartChunkCache Detail + MixinNoiseBasedChunkGenerator Cache Injection (S2)

## What to implement

This prompt supplements prompt 08 with the full detail needed to implement the noise-sample caching injection
in `MixinNoiseBasedChunkGenerator`. SmartChunkCache.java itself was covered in prompt 08 — this prompt
focuses on the injection mechanics and verification steps.

---

## Background: what WWEE does to noise sampling

William Wythers' Expanded Ecosphere registers approximately 200 biomes, each with custom noise parameters.
When `NoiseBasedChunkGenerator` generates terrain for a new chunk, it calls the biome sampler at every
column (16×16 = 256 calls per chunk) and at sub-column resolution for surface rules. Adjacent chunks share
edge samples — the same (x, z) is sampled multiple times across neighbouring chunk generation calls running
in parallel worldgen threads.

SmartChunkCache intercepts these redundant calls. Since each worldgen thread has its own ThreadLocal cache,
there is no synchronization overhead on the hot path.

---

## Target methods in NoiseBasedChunkGenerator (1.20.1 Mojmap)

The primary target is the `Climate.Sampler` interaction. In 1.20.1, `NoiseBasedChunkGenerator` holds a
reference to a `Climate.Sampler` and calls it via:

```java
// Inside buildSurface, fillFromNoise, and related methods:
Climate.TargetPoint sample = this.climateSampler().sample(x, y, z);
```

The goal is to intercept this call pattern. Two approaches are viable:

### Approach A — @Redirect on climateSampler().sample() call site

```java
// Redirects the Climate.Sampler.sample() call at the specific call site inside fillFromNoise.
// On cache hit: returns cached Climate.TargetPoint, skipping vanilla sampler entirely.
// On cache miss: calls vanilla sampler, caches the result, returns it.
@Redirect(
    method = "fillFromNoise",
    at = @At(value = "INVOKE",
             target = "Lnet/minecraft/world/level/levelgen/Climate$Sampler;sample(III)Lnet/minecraft/world/level/levelgen/Climate$TargetPoint;")
)
private Climate.TargetPoint vc_cachedSample(Climate.Sampler sampler, int x, int y, int z) {
    long key = SmartChunkCache.packKey(x, z, 0); // type=0 for Climate sample
    Climate.TargetPoint cached = (Climate.TargetPoint) SmartChunkCache.get(key);
    if (cached != null) return cached;
    Climate.TargetPoint result = sampler.sample(x, y, z);
    SmartChunkCache.put(key, result);
    return result;
}
```

**Use Approach A if the call site is not inlined and the INVOKE target is resolvable.**

### Approach B — @Inject at HEAD of a wrapper method

If `Climate.Sampler.sample()` is called through a wrapper method in `NoiseBasedChunkGenerator` (e.g.
a private `sampleClimate(int x, int y, int z)` method), use `@Inject` with `cancellable=true` at HEAD and a
`@Local` or `CallbackInfoReturnable<Climate.TargetPoint>`:

```java
@Inject(
    method = "sampleClimate", // adjust to actual method name after decompile inspection
    at = @At("HEAD"),
    cancellable = true
)
private void vc_climateCacheCheck(int x, int y, int z,
        CallbackInfoReturnable<Climate.TargetPoint> cir) {
    long key = SmartChunkCache.packKey(x, z, 0);
    Climate.TargetPoint cached = (Climate.TargetPoint) SmartChunkCache.get(key);
    if (cached != null) cir.setReturnValue(cached);
}

@Inject(
    method = "sampleClimate", // adjust to actual method name
    at = @At("RETURN")
)
private void vc_climateCacheStore(int x, int y, int z,
        CallbackInfoReturnable<Climate.TargetPoint> cir) {
    if (!VCConfig.ENABLE_NOISE_CACHE.get()) return;
    long key = SmartChunkCache.packKey(x, z, 0);
    SmartChunkCache.put(key, cir.getReturnValue());
}
```

**Use Approach B if Approach A's INVOKE target cannot be resolved.**

---

## Key packing note

The y-coordinate is intentionally excluded from the cache key. Climate samples in WWEE are 2D (x, z only)
for biome selection. The 3D noise scalar samples use a different cache key type (`type=1`). If you implement
scalar noise caching, use:

```java
long key = SmartChunkCache.packKey(x, z, 1); // type=1 for scalar noise
```

But do not cache 3D values with the 2D key — they will produce incorrect results.

---

## Verification steps

After implementation, verify with spark profiler:
1. Profile `NoiseBasedChunkGenerator.fillFromNoise` before and after enabling the cache.
2. The cache hit rate should be >60% on WWEE worlds. Check by adding a simple AtomicLong hit/miss counter
   to `SmartChunkCache.get()` and logging it every 5 minutes.
3. Memory: with 4096 entries per thread and ~4 worldgen threads, peak cache RAM ≈ 4096 × 4 × ~80 bytes
   per `Climate.TargetPoint` ≈ ~1.3 MB total. Acceptable.

---

## Constraints

- Never share the ThreadLocal map between threads — that is the entire point of ThreadLocal.
- The `packKey` function must be collision-free for typical coordinate ranges (±30 million blocks = ±30M/16 =
  ±1.875M chunks). 24 bits covers ±8M chunks — more than sufficient.
- When `ENABLE_NOISE_CACHE` is false, both `get()` and `put()` must be no-ops with no map allocation.
- The ThreadLocal initializer calls `VCConfig.NOISE_CACHE_SIZE.get()` — ensure VCConfig is initialized before
  any worldgen thread can start.

## Files to create/edit

- `src/main/java/com/velocitycore/system/SmartChunkCache.java` (may already exist from prompt 08)
- `src/main/java/com/velocitycore/mixin/MixinNoiseBasedChunkGenerator.java` (add cache injection alongside decoration injection from prompt 08)
