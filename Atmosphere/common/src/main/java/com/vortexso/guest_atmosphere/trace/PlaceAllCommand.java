package com.vortexso.guest_atmosphere.trace;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.vortexso.guest_atmosphere.block.Coating;
import com.vortexso.guest_atmosphere.block.Coating.Base;
import com.vortexso.guest_atmosphere.block.Coating.Coat;
import com.vortexso.guest_atmosphere.block.Coating.Shape;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

public final class PlaceAllCommand {
  private PlaceAllCommand() {}

  private static final Coat[] GROUPS = {Coat.SNOWY, null, Coat.SANDY};

  private static final int CLUSTER_GAP = 1;
  private static final boolean STACK_VERTICAL = false;

  public static void register(
      CommandDispatcher<CommandSourceStack> dispatcher,
      CommandBuildContext buildContext,
      Commands.CommandSelection selection) {
    dispatcher.register(
        Commands.literal("guest")
            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .then(
                Commands.literal("atmosphere")
                    .then(
                        Commands.literal("place_all")
                            .executes(
                                context ->
                                    run(
                                        context.getSource(),
                                        BlockPos.containing(context.getSource().getPosition())))
                            .then(
                                Commands.argument("pos", BlockPosArgument.blockPos())
                                    .executes(PlaceAllCommand::runAt)))));
  }

  private static int runAt(CommandContext<CommandSourceStack> context) {
    return run(context.getSource(), BlockPosArgument.getBlockPos(context, "pos"));
  }

  private static int run(CommandSourceStack source, BlockPos origin) {
    ServerLevel level = source.getLevel();
    int placed = 0;
    int cluster = 0;

    for (Base base : Base.values()) {
      for (int g = 0; g < GROUPS.length; g++) {
        Coat coat = GROUPS[g];
        for (Shape shape : Shape.values()) {
          BlockState state = stateFor(base, shape, coat);
          if (state == null) {
            continue;
          }
          int x = origin.getX() + shape.ordinal();
          int y = origin.getY();
          int z = origin.getZ();
          if (STACK_VERTICAL) {
            y += g;
            z += cluster * (1 + CLUSTER_GAP);
          } else {
            z += cluster * (GROUPS.length + CLUSTER_GAP) + g;
          }
          level.setBlock(new BlockPos(x, y, z), state, Block.UPDATE_ALL);
          placed++;
        }
      }
      cluster++;
    }

    return placed;
  }

  private static @Nullable BlockState stateFor(Base base, Shape shape, @Nullable Coat coat) {
    Block vanilla = base.block(shape);
    if (vanilla == null) {
      return null;
    }
    BlockState state = vanilla.defaultBlockState();
    return coat == null ? state : Coating.coat(state, coat);
  }
}
