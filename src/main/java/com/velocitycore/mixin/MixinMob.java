package com.velocitycore.mixin;

import com.velocitycore.system.EntityActivationManager;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin on net.minecraft.world.entity.Mob.
 *
 * Injection:
 *   vc_activationCheck — S4 EntityActivationManager: skip tick for distant/stressed mobs.
 *
 * Only Mob subclasses are throttled (not players, projectiles, or non-mob entities).
 * Mobs in the NEAR zone (0–32 blocks) are never throttled.
 *
 * See .cursor/prompts/05_entityactivation.md for full implementation brief.
 */
@Mixin(Mob.class)
public abstract class MixinMob {

    // Injection: Entity activation check (S4)
    // Rationale: HEAD with cancellable=true on Mob.tick() is the earliest point to skip the
    // entire mob tick. Injecting here means zero AI, goal evaluation, or physics work is done
    // for throttled mobs. @Inject on Mob (not LivingEntity or Entity) ensures only Mob
    // subclasses are throttled, not players or other non-mob entities.
    @Inject(
        method = "tick",
        at = @At("HEAD"),
        cancellable = true
    )
    private void vc_activationCheck(CallbackInfo ci) {
        if (!EntityActivationManager.shouldTick((Mob)(Object)this)) {
            ci.cancel();
        }
    }
}
