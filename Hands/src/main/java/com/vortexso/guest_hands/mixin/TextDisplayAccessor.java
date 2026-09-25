package com.vortexso.guest_hands.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Display.TextDisplay.class)
public interface TextDisplayAccessor {
  @Invoker("getText")
  Component guest_hands$getText();

  @Invoker("setText")
  void guest_hands$setText(Component text);
}
