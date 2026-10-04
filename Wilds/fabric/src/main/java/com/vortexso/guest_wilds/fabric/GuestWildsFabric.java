package com.vortexso.guest_wilds.fabric;

import com.vortexso.guest_wilds.GuestWilds;
import com.vortexso.guest_wilds.WildsEvents;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.world.entity.Mob;

public final class GuestWildsFabric implements ModInitializer {
  @Override
  public void onInitialize() {
    GuestWilds.spawnReason = Mob::spawnReason;
    GuestWilds.init();
    ServerEntityEvents.ALLOW_LOAD.register(
        (entity, level, reason, fromDisk) -> SpawnHooks.allowLoad(entity));
    ServerLivingEntityEvents.ALLOW_DAMAGE.register(
        (entity, source, amount) -> WildsEvents.allowDamage(entity, source));
    ServerLivingEntityEvents.MOB_CONVERSION.register(
        (previous, converted, params) -> WildsEvents.onConversion(previous, converted));
  }
}
