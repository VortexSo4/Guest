package com.vortexso.guest_architects.fabric.mixin;

import com.vortexso.guest_architects.ArchitectsEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class PlayerMixin {
  @Inject(method = "onEnchantmentPerformed", at = @At("TAIL"))
  private void guestArchitects$enchant(ItemStack itemStack, int enchantmentCost, CallbackInfo ci) {
    ArchitectsEvents.onEnchant((Player) (Object) this);
  }
}
