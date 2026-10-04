package com.vortexso.guest_architects.fabric.mixin;

import com.vortexso.guest_architects.ArchitectsEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hoglin.class)
public abstract class HoglinMixin {
  @Shadow private int timeInOverworld;

  @Inject(
      method = "customServerAiStep",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/entity/monster/hoglin/Hoglin;makeSound(Lnet/minecraft/sounds/SoundEvent;)V"),
      cancellable = true)
  private void guestArchitects$delayConversion(ServerLevel level, CallbackInfo ci) {
    if (ArchitectsEvents.delayConversion(
        (LivingEntity) (Object) this, EntityType.ZOGLIN, timer -> this.timeInOverworld = timer)) {
      ci.cancel();
    }
  }
}
