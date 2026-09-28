package com.vortexso.guest_hands.station;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

interface Station {

  String id();

  boolean enabled();

  boolean accepts(BlockState state);

  boolean storesItems();

  boolean use(
      ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player, BlockHitResult hit);

  default boolean punchFace(Direction face) {
    return true;
  }

  default boolean punch(ServerLevel level, BlockPos pos, ServerPlayer player, Vec3 hit) {
    return false;
  }

  default boolean claimsUse(Player player, BlockHitResult hit) {
    return !(player.isSecondaryUseActive() && !player.getMainHandItem().isEmpty());
  }

  default void sync(ServerLevel level, BlockEntity blockEntity) {}
}
