package com.vortexso.guest_atmosphere.fabric.mixin;

import com.vortexso.guest_atmosphere.client.VegetationTint;
import net.minecraft.client.color.block.BlockColors;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockColors.class)
public abstract class BlockColorsMixin {
  @Inject(method = "createDefault", at = @At("RETURN"))
  private static void guestAtmosphere$tints(CallbackInfoReturnable<BlockColors> cir) {
    VegetationTint.registerTints(cir.getReturnValue());
  }
}
