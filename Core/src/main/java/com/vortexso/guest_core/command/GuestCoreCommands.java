package com.vortexso.guest_core.command;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.vortexso.guest_core.GuestCore;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.world.GuestWeather;
import com.vortexso.guest_core.api.world.WeatherState;
import com.vortexso.guest_core.debug.GuestDebug;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
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

/**
 * Root of the {@code /guest} debug command tree. Addons add their own {@code /guest <addon>}
 * branches; Brigadier merges literals registered separately.
 */
@EventBusSubscriber(modid = GuestCore.MODID)
public final class GuestCoreCommands {
  private GuestCoreCommands() {}

  @SubscribeEvent
  public static void register(RegisterCommandsEvent event) {
    event
        .getDispatcher()
        .register(
            Commands.literal("guest")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("time")
                        .then(
                            Commands.literal("add")
                                .then(
                                    Commands.argument("days", LongArgumentType.longArg(1L))
                                        .executes(GuestCoreCommands::addDays))))
                .then(Commands.literal("calendar").executes(GuestCoreCommands::calendar))
                .then(Commands.literal("weather").executes(GuestCoreCommands::weather))
                .then(
                    Commands.literal("debug")
                        .then(
                            Commands.argument("channel", StringArgumentType.word())
                                .suggests(
                                    (context, builder) ->
                                        SharedSuggestionProvider.suggest(
                                            GuestDebug.channels(), builder))
                                .executes(GuestCoreCommands::toggleDebug))));
  }

  /**
   * Jumps the clock without ticking. Every Guest system must catch up from elapsed time, so this is
   * also the main tool for checking what years of absence do to the world.
   */
  private static int addDays(CommandContext<CommandSourceStack> context) {
    long days = LongArgumentType.getLong(context, "days");
    ServerLevel level = context.getSource().getServer().overworld();
    ServerClockManager clockManager = level.clockManager();
    Holder<WorldClock> overworldClock =
        level
            .registryAccess()
            .lookupOrThrow(Registries.WORLD_CLOCK)
            .getOrThrow(WorldClocks.OVERWORLD);

    long targetTicks;
    try {
      targetTicks =
          Math.addExact(
              clockManager.getTotalTicks(overworldClock),
              Math.multiplyExact(days, GuestTime.TICKS_PER_DAY));
    } catch (ArithmeticException exception) {
      context.getSource().sendFailure(Component.translatable("guest_core.command.time.too_large"));
      return 0;
    }

    clockManager.setTotalTicks(overworldClock, targetTicks);
    context
        .getSource()
        .sendSuccess(() -> Component.translatable("guest_core.command.time.added", days), true);
    return 1;
  }

  private static int calendar(CommandContext<CommandSourceStack> context) {
    long time = GuestTime.gameTime(context.getSource().getLevel());
    context
        .getSource()
        .sendSuccess(
            () ->
                Component.translatable(
                    "guest_core.command.calendar",
                    GuestTime.year(time),
                    GuestTime.season(time).displayName(),
                    GuestTime.weekOfSeason(time) + 1,
                    GuestTime.weekday(time) + 1,
                    Component.translatable(
                        "guest_core.moon." + GuestTime.moonPhase(time).getSerializedName())),
            false);
    return 1;
  }

  private static int weather(CommandContext<CommandSourceStack> context) {
    ServerLevel level = context.getSource().getLevel();
    BlockPos pos = BlockPos.containing(context.getSource().getPosition());
    WeatherState state = GuestWeather.get(level, pos, GuestTime.gameTime(level));
    context
        .getSource()
        .sendSuccess(
            () ->
                Component.translatable(
                    "guest_core.command.weather",
                    state.type().displayName(),
                    String.format(java.util.Locale.ROOT, "%.2f", state.intensity()),
                    String.format(java.util.Locale.ROOT, "%.2f", state.wind()),
                    Component.translatable(
                        state.aurora() ? "guest_core.common.yes" : "guest_core.common.no")),
            false);
    return 1;
  }

  private static int toggleDebug(CommandContext<CommandSourceStack> context) {
    String channel = StringArgumentType.getString(context, "channel");
    if (!GuestDebug.channels().contains(channel)) {
      context
          .getSource()
          .sendFailure(Component.translatable("guest_core.command.debug.unknown", channel));
      return 0;
    }
    boolean enabled = GuestDebug.toggle(channel);
    context
        .getSource()
        .sendSuccess(
            () ->
                Component.translatable(
                    enabled ? "guest_core.command.debug.on" : "guest_core.command.debug.off",
                    channel),
            false);
    return 1;
  }
}
