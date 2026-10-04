package com.vortexso.guest_architects.fabric.mixin;

import com.vortexso.guest_architects.ArchitectsEvents;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
  @Inject(method = "gameEvent", at = @At("HEAD"), cancellable = true)
  private void guestArchitects$gameEvent(
      Holder<GameEvent> gameEvent, Vec3 position, GameEvent.Context context, CallbackInfo ci) {
    Entity cause = context.sourceEntity();
    if (cause != null
        && ArchitectsEvents.cancelGameEvent(
            (ServerLevel) (Object) this, cause, gameEvent, position)) {
      ci.cancel();
    }
  }

  @Inject(
      method = "tickNonPassenger",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V"))
  private void guestArchitects$entityTick(Entity entity, CallbackInfo ci) {
    ArchitectsEvents.onEntityTick(entity);
  }
}
