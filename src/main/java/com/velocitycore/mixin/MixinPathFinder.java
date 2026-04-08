package com.velocitycore.mixin;

import com.velocitycore.system.PathfindingThrottle;
import com.velocitycore.system.RuntimeSystemGate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin on net.minecraft.world.entity.ai.navigation.PathFinder.
 *
 * Injections:
 *   1. vc_setupPathBudget — S12: set per-call node budget at findPath() start
 *   2. vc_checkNodeBudget — S12: cancel path search when node budget is exceeded
 *
 * Applies to all EntityTypes including modded mobs — PathFinder is a vanilla class.
 *
 * See .cursor/prompts/07_mobcapguard.md for full implementation brief.
 * NOTE: The exact INVOKE target for the BinaryHeap.pop() call must be verified against
 * Forge 1.20.1 decompiled sources before finalizing injection 2.
 */
@Mixin(PathFinder.class)
public abstract class MixinPathFinder {

    // Injection 1: Setup path budget (S12)
    // Rationale: HEAD of findPath() is where we set up the per-call node budget using
    // PathfindingThrottle.prepareBudget(). The mob reference is obtained from the entity parameter.
    @Inject(
        method = "findPath",
        at = @At("HEAD")
    )
    private void vc_setupPathBudget(BlockPathTypes[] pathTypes, Entity entity, int maxVisitedNodes,
            CallbackInfoReturnable<Path> cir) {
        if (!RuntimeSystemGate.isEnabled("S12_PATHFINDING", true)) return;
        Mob mob = (entity instanceof Mob m) ? m : null;
        PathfindingThrottle.prepareBudget(mob);
    }

    // Injection 2: Check node budget (S12)
    // Rationale: Intercepting at the BinaryHeap.pop() call inside the A* loop allows checking
    // the node budget on each evaluation without modifying the algorithm. When budget is exceeded,
    // returning null is safe — PathFinder returns null to signal "no path found" in vanilla.
    //
    // IMPORTANT: Verify the exact INVOKE target in Forge 1.20.1 sources before enabling.
    // The target below uses the Mojmap class path — confirm it matches the decompilation.
    @Inject(
        method = "findPath",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/world/level/pathfinder/BinaryHeap;pop()Lnet/minecraft/world/level/pathfinder/Node;"),
        cancellable = true
    )
    private void vc_checkNodeBudget(BlockPathTypes[] pathTypes, Entity entity, int maxVisitedNodes,
            CallbackInfoReturnable<Path> cir) {
        if (!RuntimeSystemGate.isEnabled("S12_PATHFINDING", true)) return;
        if (PathfindingThrottle.exceedsBudget()) {
            cir.setReturnValue(null);
        }
    }
}
