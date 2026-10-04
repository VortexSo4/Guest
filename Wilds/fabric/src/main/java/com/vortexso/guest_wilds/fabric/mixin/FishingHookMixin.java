package com.vortexso.guest_wilds.fabric.mixin;

import com.vortexso.guest_wilds.WildsEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FishingHook.class)
abstract class FishingHookMixin {
  @Shadow private int nibble;

  @Shadow private @Nullable Entity hookedIn;

  @Shadow
  public abstract @Nullable Player getPlayerOwner();

  @Shadow
  private boolean shouldStopFishing(Player owner) {
    throw new AssertionError();
  }

  @Inject(method = "retrieve", at = @At("HEAD"), cancellable = true)
  private void guestWilds$fishFromShoal(ItemStack rod, CallbackInfoReturnable<Integer> cir) {
    FishingHook hook = (FishingHook) (Object) this;
    Player owner = getPlayerOwner();
    if (!hook.level().isClientSide()
        && owner != null
        && !shouldStopFishing(owner)
        && hookedIn == null
        && nibble > 0
        && WildsEvents.onFished(hook, owner)) {
      hook.discard();
      cir.setReturnValue(hook.onGround() ? 2 : 1);
    }
  }
}
