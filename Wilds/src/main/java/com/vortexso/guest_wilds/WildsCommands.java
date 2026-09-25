package com.vortexso.guest_wilds;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.event.RouteTrafficEvent;
import com.vortexso.guest_core.api.world.GuestWildlife;
import com.vortexso.guest_wilds.fish.Shoals;
import com.vortexso.guest_wilds.herd.Herds;
import com.vortexso.guest_wilds.lair.LairSpecies;
import com.vortexso.guest_wilds.lair.Lairs;
import com.vortexso.guest_wilds.path.PathWear;
import java.util.Comparator;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** {@code /guest wilds ...}: inspect nodes and push the simulation for testing. */
@EventBusSubscriber(modid = GuestWilds.MODID)
public final class WildsCommands {
  private static final int SEARCH_RADIUS = 256;

  private WildsCommands() {}

  @SubscribeEvent
  public static void register(RegisterCommandsEvent event) {
    event
        .getDispatcher()
        .register(
            Commands.literal("guest")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("wilds")
                        .then(Commands.literal("lair").executes(WildsCommands::lair))
                        .then(Commands.literal("herd").executes(WildsCommands::herd))
                        .then(Commands.literal("shoal").executes(WildsCommands::shoal))
                        .then(
                            Commands.literal("pressure")
                                .executes(context -> pressure(context, 64))
                                .then(
                                    Commands.argument("radius", IntegerArgumentType.integer(8, 512))
                                        .executes(
                                            context ->
                                                pressure(
                                                    context,
                                                    IntegerArgumentType.getInteger(
                                                        context, "radius")))))
                        .then(
                            Commands.literal("wear")
                                .then(
                                    Commands.argument("amount", DoubleArgumentType.doubleArg(0.0))
                                        .executes(WildsCommands::wear)))
                        .then(
                            Commands.literal("route")
                                .then(
                                    Commands.argument("from", BlockPosArgument.blockPos())
                                        .then(
                                            Commands.argument("to", BlockPosArgument.blockPos())
                                                .then(
                                                    Commands.argument(
                                                            "trips",
                                                            DoubleArgumentType.doubleArg(0.0))
                                                        .executes(WildsCommands::route)))))
                        .then(
                            Commands.literal("simulate")
                                .then(
                                    Commands.argument(
                                            "days", LongArgumentType.longArg(1L, 100_000L))
                                        .executes(WildsCommands::simulate)))));
  }

  private static ServerLevel overworld(CommandContext<CommandSourceStack> context) {
    return context.getSource().getServer().overworld();
  }

  private static BlockPos here(CommandContext<CommandSourceStack> context) {
    return BlockPos.containing(context.getSource().getPosition());
  }

  private static boolean inOverworld(CommandContext<CommandSourceStack> context) {
    if (context.getSource().getLevel().dimension() == Level.OVERWORLD) {
      return true;
    }
    context.getSource().sendFailure(Component.translatable("guest_wilds.command.overworld_only"));
    return false;
  }

  private static int lair(CommandContext<CommandSourceStack> context) {
    if (!inOverworld(context)) {
      return 0;
    }
    ServerLevel level = overworld(context);
    BlockPos pos = here(context);
    Lairs lairs = Lairs.get(level);
    Lairs.Node node =
        lairs.nodesNear(pos, SEARCH_RADIUS).stream()
            .min(Comparator.comparingDouble(n -> n.pos.distSqr(pos)))
            .orElse(null);
    if (node == null) {
      context.getSource().sendFailure(Component.translatable("guest_wilds.command.lair.none"));
      return 0;
    }
    lairs.advance(level, node);
    MutableComponent species = Component.empty();
    for (LairSpecies s : LairSpecies.VALUES) {
      species
          .append(" ")
          .append(
              Component.translatable(
                  "guest_wilds.command.lair.species",
                  Component.translatable("guest_wilds.species." + s.id()),
                  format(node.population(s)),
                  node.concrete(s),
                  format(node.emigrants(s))));
    }
    context
        .getSource()
        .sendSuccess(
            () ->
                Component.translatable(
                        "guest_wilds.command.lair",
                        node.pos.toShortString(),
                        format(node.openness),
                        Component.translatable(
                            node.ridersEstablished()
                                ? "guest_core.common.yes"
                                : "guest_core.common.no"))
                    .append(species),
            false);
    return 1;
  }

  private static int herd(CommandContext<CommandSourceStack> context) {
    if (!inOverworld(context)) {
      return 0;
    }
    ServerLevel level = overworld(context);
    BlockPos pos = here(context);
    Herds herds = Herds.get(level);
    Herds.Herd herd =
        herds.herds().stream()
            .filter(h -> h.center().distSqr(pos.atY(0)) <= (double) SEARCH_RADIUS * SEARCH_RADIUS)
            .min(Comparator.comparingDouble(h -> h.center().distSqr(pos.atY(0))))
            .orElse(null);
    if (herd == null) {
      context.getSource().sendFailure(Component.translatable("guest_wilds.command.herd.none"));
      return 0;
    }
    herds.advance(level, herd);
    long now = GuestTime.gameTime(level);
    context
        .getSource()
        .sendSuccess(
            () ->
                Component.translatable(
                    "guest_wilds.command.herd",
                    Component.translatable(
                        net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                            .getValue(Identifier.parse(herd.species))
                            .getDescriptionId()),
                    format(herd.animals()),
                    herd.concrete(),
                    herd.center().getX() + ", " + herd.center().getZ(),
                    format(herds.vegetationAt(herd.center(), now))),
            false);
    return 1;
  }

  private static int shoal(CommandContext<CommandSourceStack> context) {
    if (!inOverworld(context)) {
      return 0;
    }
    ServerLevel level = overworld(context);
    BlockPos pos = here(context);
    if (!level.getFluidState(pos).is(FluidTags.WATER)
        && !level.getFluidState(pos.below()).is(FluidTags.WATER)) {
      context.getSource().sendFailure(Component.translatable("guest_wilds.command.shoal.none"));
      return 0;
    }
    Shoals.Shoal shoal = Shoals.get(level).at(level, pos);
    long now = GuestTime.gameTime(level);
    context
        .getSource()
        .sendSuccess(
            () ->
                Component.translatable(
                    "guest_wilds.command.shoal",
                    Component.translatable(
                        "guest_wilds.water." + shoal.water.name().toLowerCase(Locale.ROOT)),
                    format(shoal.fish()),
                    format(Shoals.capacity(shoal, now)),
                    format(Shoals.salmonShare(shoal, now)),
                    shoal.concrete()),
            false);
    return 1;
  }

  private static int pressure(CommandContext<CommandSourceStack> context, int radius) {
    if (!inOverworld(context)) {
      return 0;
    }
    double pressure = GuestWildlife.hostilePressure(overworld(context), here(context), radius);
    context
        .getSource()
        .sendSuccess(
            () -> Component.translatable("guest_wilds.command.pressure", radius, format(pressure)),
            false);
    return 1;
  }

  private static int wear(CommandContext<CommandSourceStack> context) {
    if (!inOverworld(context)) {
      return 0;
    }
    ServerLevel level = overworld(context);
    BlockPos ground = here(context).below();
    double amount = DoubleArgumentType.getDouble(context, "amount");
    PathWear wear = PathWear.get(level);
    wear.add(level, ground.getX(), ground.getY(), ground.getZ(), amount);
    context
        .getSource()
        .sendSuccess(
            () -> Component.translatable("guest_wilds.command.wear", format(amount)), true);
    return 1;
  }

  /** Posts a real Core event, so this exercises exactly what Settlements triggers. */
  private static int route(CommandContext<CommandSourceStack> context) {
    if (!inOverworld(context)) {
      return 0;
    }
    BlockPos from = BlockPosArgument.getBlockPos(context, "from");
    BlockPos to = BlockPosArgument.getBlockPos(context, "to");
    double trips = DoubleArgumentType.getDouble(context, "trips");
    NeoForge.EVENT_BUS.post(
        new RouteTrafficEvent(
            overworld(context),
            from,
            to,
            trips,
            Identifier.fromNamespaceAndPath(GuestWilds.MODID, "debug")));
    context
        .getSource()
        .sendSuccess(
            () -> Component.translatable("guest_wilds.command.route", format(trips)), true);
    return 1;
  }

  /**
   * Same clock jump as {@code /guest time add}, followed by catching up every known node, so the
   * result of long absences can be checked at once.
   */
  private static int simulate(CommandContext<CommandSourceStack> context) {
    long days = LongArgumentType.getLong(context, "days");
    ServerLevel level = overworld(context);
    ServerClockManager clocks = level.clockManager();
    Holder<WorldClock> clock =
        level
            .registryAccess()
            .lookupOrThrow(Registries.WORLD_CLOCK)
            .getOrThrow(WorldClocks.OVERWORLD);
    clocks.setTotalTicks(clock, clocks.getTotalTicks(clock) + days * GuestTime.TICKS_PER_DAY);
    Lairs lairs = Lairs.get(level);
    lairs.nodes().forEach(node -> lairs.advance(level, node));
    Herds herds = Herds.get(level);
    herds.herds().forEach(herd -> herds.advance(level, herd));
    PathWear.get(level).refreshAll(level);
    context
        .getSource()
        .sendSuccess(() -> Component.translatable("guest_wilds.command.simulate", days), true);
    return 1;
  }

  private static String format(double value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }
}
