package com.vortexso.guest_settlements.mixin;

import net.minecraft.world.entity.schedule.Activity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Activity.class)
public interface ActivityAccessor {
  @Invoker("<init>")
  static Activity guestSettlements$create(String name) {
    throw new AssertionError();
  }
}
