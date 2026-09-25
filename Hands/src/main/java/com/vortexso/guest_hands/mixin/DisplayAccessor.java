package com.vortexso.guest_hands.mixin;

import com.mojang.math.Transformation;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Display setters are private; spawning configured displays otherwise needs an NBT round trip. */
@Mixin(Display.class)
public interface DisplayAccessor {
  @Invoker("setTransformation")
  void guest_hands$setTransformation(Transformation transformation);

  @Invoker("setBillboardConstraints")
  void guest_hands$setBillboard(Display.BillboardConstraints constraints);
}
