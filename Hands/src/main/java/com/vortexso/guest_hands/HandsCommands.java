package com.vortexso.guest_hands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.vortexso.guest_hands.grapple.Grapple;
import com.vortexso.guest_hands.station.Stations;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = GuestHands.MODID)
public final class HandsCommands {
  private HandsCommands() {}

  @SubscribeEvent
  static void register(RegisterCommandsEvent event) {
    event
        .getDispatcher()
        .register(
            Commands.literal("guest")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("hands")
                        .then(
                            Commands.literal("use")
                                .then(
                                    Commands.argument("pos", BlockPosArgument.blockPos())
                                        .then(
                                            Commands.argument("hit", Vec3Argument.vec3(false))
                                                .executes(context -> use(context, false))
                                                .then(
                                                    Commands.literal("sneak")
                                                        .executes(context -> use(context, true))))))
                        .then(
                            Commands.literal("punch")
                                .then(
                                    Commands.argument("pos", BlockPosArgument.blockPos())
                                        .then(
                                            Commands.argument("hit", Vec3Argument.vec3(false))
                                                .executes(HandsCommands::punch))))
                        .then(
                            Commands.literal("button")
                                .then(
                                    Commands.argument("id", IntegerArgumentType.integer(0))
                                        .executes(HandsCommands::button)))
                        .then(
                            Commands.literal("grip")
                                .then(
                                    Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(HandsCommands::grip)))));
  }

  private static int use(CommandContext<CommandSourceStack> context, boolean sneak)
      throws CommandSyntaxException {
    ServerPlayer player = context.getSource().getPlayerOrException();
    BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
    Vec3 hit = Vec3Argument.getVec3(context, "hit");
    BlockHitResult target = new BlockHitResult(hit, faceAt(pos, hit), pos, false);
    boolean wasSneaking = player.isShiftKeyDown();
    player.setShiftKeyDown(sneak);
    boolean handled =
        player
            .gameMode
            .useItemOn(
                player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND, target)
            .consumesAction();
    player.setShiftKeyDown(wasSneaking);
    return report(context, "guest_hands.command.use", handled, pos.toShortString());
  }

  private static int punch(CommandContext<CommandSourceStack> context)
      throws CommandSyntaxException {
    ServerPlayer player = context.getSource().getPlayerOrException();
    BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
    boolean handled = Stations.punchAt(player, pos, Vec3Argument.getVec3(context, "hit"));
    return report(context, "guest_hands.command.punch", handled, pos.toShortString());
  }

  private static int button(CommandContext<CommandSourceStack> context)
      throws CommandSyntaxException {
    ServerPlayer player = context.getSource().getPlayerOrException();
    int id = IntegerArgumentType.getInteger(context, "id");
    AbstractContainerMenu menu = player.containerMenu;
    boolean handled = menu.stillValid(player) && menu.clickMenuButton(player, id);
    if (handled) {
      menu.broadcastChanges();
    }
    return report(context, "guest_hands.command.button", handled, id);
  }

  private static int grip(CommandContext<CommandSourceStack> context)
      throws CommandSyntaxException {
    ServerPlayer player = context.getSource().getPlayerOrException();
    BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
    Grapple.grip(player, pos);
    boolean handled = Grapple.attachment(player) != null;
    return report(context, "guest_hands.command.grip", handled, pos.toShortString());
  }

  private static int report(
      CommandContext<CommandSourceStack> context, String key, boolean handled, Object subject) {
    Component result =
        Component.translatable(
            handled ? "guest_hands.command.handled" : "guest_hands.command.passed");
    context.getSource().sendSuccess(() -> Component.translatable(key, subject, result), false);
    return handled ? 1 : 0;
  }

  private static Direction faceAt(BlockPos pos, Vec3 hit) {
    double x = hit.x - pos.getX();
    double y = hit.y - pos.getY();
    double z = hit.z - pos.getZ();
    Direction face = Direction.UP;
    double best = 1.0 - y;
    double[] distances = {y, z, 1.0 - z, x, 1.0 - x};
    Direction[] faces = {
      Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    for (int i = 0; i < faces.length; i++) {
      if (distances[i] < best) {
        best = distances[i];
        face = faces[i];
      }
    }
    return face;
  }
}
