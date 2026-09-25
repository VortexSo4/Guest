package com.vortexso.guest_settlements.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.history.GuestHistory;
import com.vortexso.guest_settlements.GuestSettlements;
import com.vortexso.guest_settlements.SettlementsConfig;
import com.vortexso.guest_settlements.illager.IllagerRhythm;
import com.vortexso.guest_settlements.village.Caravans;
import com.vortexso.guest_settlements.village.VillageNode;
import com.vortexso.guest_settlements.village.VillageSimulator;
import com.vortexso.guest_settlements.village.VillageState;
import com.vortexso.guest_settlements.village.VillageWorldManager;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** {@code /guest settlements ...}: inspect and fast-forward villages. */
@EventBusSubscriber(modid = GuestSettlements.MODID)
public final class SettlementsCommands {
  private static final int HISTORY_RADIUS = 96;

  private SettlementsCommands() {}

  @SubscribeEvent
  public static void register(RegisterCommandsEvent event) {
    event
        .getDispatcher()
        .register(
            Commands.literal("guest")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("settlements")
                        .then(Commands.literal("info").executes(SettlementsCommands::info))
                        .then(
                            Commands.literal("simulate")
                                .then(
                                    Commands.argument(
                                            "days", IntegerArgumentType.integer(1, 1_000_000))
                                        .executes(SettlementsCommands::simulate)))
                        .then(Commands.literal("caravans").executes(SettlementsCommands::caravans))
                        .then(Commands.literal("history").executes(SettlementsCommands::history))
                        .then(Commands.literal("patrols").executes(SettlementsCommands::patrols))));
  }

  private static int info(CommandContext<CommandSourceStack> context) {
    ServerLevel level = context.getSource().getLevel();
    VillageWorldManager manager = VillageWorldManager.get(level);
    VillageNode node = nearest(context, manager);
    if (node == null) {
      return 0;
    }
    VillageState state = node.state();
    long today = GuestTime.day(GuestTime.gameTime(level));
    VillageSimulator.Rates rates =
        VillageSimulator.rates(
            state,
            VillageWorldManager.severeOnDay(level, node.center(), today) ? 1.0 : 0.0,
            manager.pressure(node),
            today,
            SettlementsConfig.parameters());
    send(
        context,
        Component.translatable(
            "guest_settlements.command.info",
            node.center().toShortString(),
            Component.translatable(
                manager.isActive(node)
                    ? "guest_settlements.command.info.active"
                    : "guest_settlements.command.info.simulated"),
            state.population(),
            state.children(),
            state.beds(),
            state.farmland(),
            format(state.foodReserve()),
            format(rates.production()),
            format(rates.consumption()),
            format(rates.births()),
            format(rates.deaths()),
            format(manager.pressure(node)),
            Component.translatable(
                state.fallen() ? "guest_core.common.yes" : "guest_core.common.no"),
            state.day()));
    return 1;
  }

  private static int simulate(CommandContext<CommandSourceStack> context) {
    VillageWorldManager manager = VillageWorldManager.get(context.getSource().getLevel());
    VillageNode node = nearest(context, manager);
    if (node == null) {
      return 0;
    }
    int days = IntegerArgumentType.getInteger(context, "days");
    VillageState before = node.state();
    long started = System.nanoTime();
    VillageState after = manager.forceSimulate(node, days);
    double millis = (System.nanoTime() - started) / 1_000_000.0;
    send(
        context,
        Component.translatable(
            "guest_settlements.command.simulate",
            days,
            before.population(),
            after.population(),
            format(before.foodReserve()),
            format(after.foodReserve()),
            format(millis)));
    return 1;
  }

  private static int caravans(CommandContext<CommandSourceStack> context) {
    ServerLevel level = context.getSource().getLevel();
    VillageWorldManager manager = VillageWorldManager.get(level);
    long now = GuestTime.gameTime(level);
    List<Caravans.Caravan> caravans = manager.caravansInTransit(now);
    send(context, Component.translatable("guest_settlements.command.caravans", caravans.size()));
    double day = now / (double) GuestTime.TICKS_PER_DAY;
    for (Caravans.Caravan caravan : caravans) {
      VillageNode from = manager.node(caravan.fromId());
      VillageNode to = manager.node(caravan.toId());
      if (from != null && to != null) {
        send(
            context,
            Component.translatable(
                caravan.lost()
                    ? "guest_settlements.command.caravans.lost"
                    : "guest_settlements.command.caravans.entry",
                from.center().toShortString(),
                to.center().toShortString(),
                String.format(Locale.ROOT, "%.0f", caravan.progress(day) * 100.0)));
      }
    }
    return caravans.size();
  }

  private static int history(CommandContext<CommandSourceStack> context) {
    ServerLevel level = context.getSource().getLevel();
    BlockPos pos = BlockPos.containing(context.getSource().getPosition());
    var records =
        GuestHistory.get(level)
            .near(
                pos,
                HISTORY_RADIUS,
                record -> record.kind().getNamespace().equals(GuestSettlements.MODID));
    send(context, Component.translatable("guest_settlements.command.history", records.size()));
    long now = GuestTime.gameTime(level);
    for (var record : records) {
      send(
          context,
          Component.translatable(
              "guest_settlements.command.history.entry",
              Component.translatable("guest_settlements.history." + record.kind().getPath()),
              (now - record.gameTime()) / GuestTime.TICKS_PER_DAY,
              record.pos().toShortString(),
              record.weight()));
    }
    return records.size();
  }

  private static int patrols(CommandContext<CommandSourceStack> context) {
    send(
        context,
        Component.translatable(
            "guest_settlements.command.patrols",
            format(IllagerRhythm.patrolRate(context.getSource().getLevel()))));
    return 1;
  }

  private static VillageNode nearest(
      CommandContext<CommandSourceStack> context, VillageWorldManager manager) {
    VillageNode node = manager.nearest(BlockPos.containing(context.getSource().getPosition()));
    if (node == null) {
      context
          .getSource()
          .sendFailure(Component.translatable("guest_settlements.command.no_village"));
    }
    return node;
  }

  private static void send(CommandContext<CommandSourceStack> context, Component message) {
    context.getSource().sendSuccess(() -> message, false);
  }

  private static String format(double value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }
}
