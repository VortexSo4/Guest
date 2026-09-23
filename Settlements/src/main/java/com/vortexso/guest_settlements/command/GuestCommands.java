package com.vortexso.guest_settlements.command;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.vortexso.guest_core.api.GuestTime;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = "guest_settlements")
public final class GuestCommands {
  private GuestCommands() {}

  @SubscribeEvent
  public static void register(RegisterCommandsEvent event) {
    event
        .getDispatcher()
        .register(
            Commands.literal("guest")
                .then(
                    Commands.literal("time")
                        .then(
                            Commands.literal("add")
                                .then(
                                    Commands.argument("days", LongArgumentType.longArg(1L))
                                        .executes(GuestCommands::addDays)))));
  }

  private static int addDays(CommandContext<CommandSourceStack> context) {
    long days = LongArgumentType.getLong(context, "days");

    ServerLevel level = context.getSource().getServer().overworld();

    ServerClockManager clockManager = level.clockManager();

    Holder<WorldClock> overworldClock =
        level
            .registryAccess()
            .lookupOrThrow(Registries.WORLD_CLOCK)
            .getOrThrow(WorldClocks.OVERWORLD);

    long currentTicks = clockManager.getTotalTicks(overworldClock);

    long deltaTicks;

    long targetTicks;

    try {
      deltaTicks = Math.multiplyExact(days, GuestTime.TICKS_PER_DAY);

      targetTicks = Math.addExact(currentTicks, deltaTicks);
    } catch (ArithmeticException exception) {
      context.getSource().sendFailure(Component.literal("Time value is too large."));

      return 0;
    }

    clockManager.setTotalTicks(overworldClock, targetTicks);

    context
        .getSource()
        .sendSuccess(() -> Component.literal("Advanced time by " + days + " days."), true);

    return 1;
  }
}
