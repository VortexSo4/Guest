package com.vortexso.guest_settlements.mixin;

import com.vortexso.guest_settlements.GuestSettlements;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNodeEvaluatorMixin extends NodeEvaluator {
  @Inject(
      method =
          "getFloorLevel(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)D",
      at = @At("RETURN"),
      cancellable = true)
  private static void guestSettlements$layerFloor(
      BlockGetter level, BlockPos pos, CallbackInfoReturnable<Double> cir) {
    BlockState state = level.getBlockState(pos);
    if (state.getBlock() instanceof SnowLayerBlock) {
      VoxelShape shape = state.getCollisionShape(level, pos);
      if (!shape.isEmpty()) {
        cir.setReturnValue(
            Math.max(cir.getReturnValue(), pos.getY() + shape.max(Direction.Axis.Y)));
      }
    }
  }

  @Inject(method = "findAcceptedNode", at = @At("RETURN"), cancellable = true)
  private void guestSettlements$digThroughCover(
      int x,
      int y,
      int z,
      int jumpSize,
      double nodeHeight,
      Direction travelDirection,
      PathType blockPathTypeCurrent,
      CallbackInfoReturnable<Node> cir) {
    Node found = cir.getReturnValue();
    if ((found == null || found.costMalus < 0.0F)
        && mob instanceof Villager
        && covered(currentContext.level(), new BlockPos(x, y, z))) {
      Node breach = getNode(x, y, z);
      breach.type = PathType.BREACH;
      breach.costMalus = Math.max(breach.costMalus, mob.getPathfindingMalus(PathType.BREACH));
      cir.setReturnValue(breach);
    }
  }

  private static boolean covered(BlockGetter level, BlockPos feet) {
    boolean cover = false;
    for (BlockPos cell : new BlockPos[] {feet, feet.above()}) {
      BlockState state = level.getBlockState(cell);
      if (state.is(GuestSettlements.CLEARABLE_COVER)) {
        cover = true;
      } else if (!state.getCollisionShape(level, cell).isEmpty()) {
        return false;
      }
    }
    BlockPos below = feet.below();
    return cover && !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
  }
}
