package com.vortexso.guest_hands.mixin;

import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Display.ItemDisplay.class)
public interface ItemDisplayAccessor {
  @Invoker("getItemStack")
  ItemStack guest_hands$getItem();

  @Invoker("setItemStack")
  void guest_hands$setItem(ItemStack stack);

  @Invoker("setItemTransform")
  void guest_hands$setItemTransform(ItemDisplayContext context);
}
