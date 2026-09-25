package com.vortexso.guest_hands.mixin;

import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 0 = lit time remaining, 1 = lit total, 2 = cooking progress, 3 = cooking total (vanilla). */
@Mixin(AbstractFurnaceBlockEntity.class)
public interface FurnaceAccessor {
  @Accessor("dataAccess")
  ContainerData guest_hands$data();
}
