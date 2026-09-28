package com.vortexso.guest_architects.block;

import com.vortexso.guest_architects.GuestArchitects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.TheEndPortalBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class ArchitectPortalBlockEntity extends TheEndPortalBlockEntity {
  public ArchitectPortalBlockEntity(BlockPos pos, BlockState state) {
    super(GuestArchitects.PORTAL_BLOCK_ENTITY.get(), pos, state);
  }
}
