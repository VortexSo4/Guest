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

/**
 * One vanilla workstation made physical. Shared controls: right click with an item puts it in,
 * empty hand performs the station's action, punching takes stored items back, and anything the
 * station does not claim falls through to vanilla (so the normal GUI is always reachable).
 */
interface Station {
  /** Short id used in display tags; never change it, tags are saved with the world. */
  String id();

  boolean enabled();

  boolean accepts(BlockState state);

  /** Whether the item displays hold the real items (drop them when the block disappears). */
  boolean storesItems();

  /** Server: returns true when the click was consumed. */
  boolean use(
      ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player, BlockHitResult hit);

  /** Faces on which a punch takes items back instead of starting to break the block. */
  default boolean punchFace(Direction face) {
    return true;
  }

  /** Server: returns true when a stored item was taken back (block breaking is cancelled). */
  default boolean punch(ServerLevel level, BlockPos pos, ServerPlayer player, Vec3 hit) {
    return false;
  }

  /**
   * Client guess whether the server will consume this click, so the client does not predict a block
   * placement. A wrong guess is harmless: the server still decides and opens menus itself.
   */
  default boolean claimsUse(Player player, BlockHitResult hit) {
    return !(player.isSecondaryUseActive() && !player.getMainHandItem().isEmpty());
  }

  /** Server: refresh mirror displays of a block entity (only stations that mirror one). */
  default void sync(ServerLevel level, BlockEntity blockEntity) {}
}
