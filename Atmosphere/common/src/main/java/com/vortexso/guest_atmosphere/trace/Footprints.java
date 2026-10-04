package com.vortexso.guest_atmosphere.trace;

import com.vortexso.guest_atmosphere.AtmosphereConfig;
import com.vortexso.guest_core.api.GuestHash;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class Footprints {
  private static final long SALT = 0x6A09E667F3BCC909L;

  private static final int MIN_LAYERS = 2;

  private static final double MOVING = 1.0E-4;

  private Footprints() {}

  public static void step(Level level, BlockPos pos, BlockState state, Entity entity) {
    if (!(level instanceof ServerLevel server)
        || !(entity instanceof LivingEntity)
        || entity.getKnownSpeed().horizontalDistanceSqr() < MOVING
        || !AtmosphereConfig.TRACES_ENABLED.get()
        || !AtmosphereConfig.FOOTPRINTS.get()
        || ChunkTraces.isFixed(level, pos)) {
      return;
    }
    int layers = state.getValue(SnowLayerBlock.LAYERS);
    if (layers <= MIN_LAYERS
        || GuestHash.unit(
                GuestHash.hash(
                    server.getSeed() ^ SALT, pos.asLong(), server.getGameTime(), entity.getId()))
            >= AtmosphereConfig.FOOTPRINT_CHANCE.get()) {
      return;
    }
    server.setBlock(pos, state.setValue(SnowLayerBlock.LAYERS, layers - 1), Block.UPDATE_ALL);
  }
}
