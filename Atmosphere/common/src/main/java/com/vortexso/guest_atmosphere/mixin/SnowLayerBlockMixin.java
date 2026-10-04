package com.vortexso.guest_atmosphere.mixin;

import com.vortexso.guest_atmosphere.trace.Footprints;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SnowLayerBlock.class)
public abstract class SnowLayerBlockMixin extends Block {
  private SnowLayerBlockMixin(Properties properties) {
    super(properties);
  }

  @Override
  public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
    Footprints.step(level, pos, state, entity);
    super.stepOn(level, pos, state, entity);
  }
}
