package com.vortexso.guest_hands.mixin;

import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 0 = brew time remaining, 1 = fuel charges (vanilla). */
@Mixin(BrewingStandBlockEntity.class)
public interface BrewingStandAccessor {
  @Accessor("dataAccess")
  ContainerData guest_hands$data();
}
