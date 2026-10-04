package com.vortexso.guest_wilds;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.event.RouteTrafficEvent;
import com.vortexso.guest_core.api.world.GuestWildlife;
import com.vortexso.guest_wilds.fish.Shoals;
import com.vortexso.guest_wilds.flora.Regrowth;
import com.vortexso.guest_wilds.flora.Spread;
import com.vortexso.guest_wilds.herd.Herds;
import com.vortexso.guest_wilds.lair.LairSpecies;
import com.vortexso.guest_wilds.lair.Lairs;
import com.vortexso.guest_wilds.path.PathWear;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

public final class WildsCommands {
  private static final int SEARCH_RADIUS = 256;
  private static final int LIST_LIMIT = 8;
  private static final int SURFACE_WAYPOINT_STEP = 8;
  private static final SimpleCommandExceptionType BAD_WAYPOINTS =
      new SimpleCommandExceptionType(Component.translatable("guest_wilds.command.route.bad"));

  private WildsCommands() {}

  public static void register(
      CommandDispatcher<CommandSourceStack> dispatcher,
      CommandBuildContext buildContext,
      Commands.CommandSelection selection) {
    dispatcher.register(
        Commands.literal("guest")
            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .then(
                Commands.literal("wilds")
                    .then(
                        Commands.literal("lair")
                            .executes(WildsCommands::lair)
                            .then(Commands.literal("traces").executes(WildsCommands::traces)))
                    .then(
                        Commands.literal("lairs")
                            .executes(context -> lairs(context, SEARCH_RADIUS))
                            .then(
                                Commands.argument("radius", IntegerArgumentType.integer(16, 10_000))
                                    .executes(
                                        context ->
                                            lairs(
                                                context,
                                                IntegerArgumentType.getInteger(
                                                    context, "radius")))))
                    .then(Commands.literal("herd").executes(WildsCommands::herd))
                    .then(
                        Commands.literal("herds")
                            .executes(context -> herds(context, SEARCH_RADIUS))
                            .then(
                                Commands.argument("radius", IntegerArgumentType.integer(16, 10_000))
                                    .executes(
                                        context ->
                                            herds(
                                                context,
                                                IntegerArgumentType.getInteger(
                                                    context, "radius")))))
                    .then(Commands.literal("shoal").executes(WildsCommands::shoal))
                    .then(
                        Commands.literal("regrowth")
                            .executes(WildsCommands::regrowth)
                            .then(Commands.literal("spread").executes(WildsCommands::spread))
                            .then(
                                Commands.literal("fell")
                                    .then(
                                        Commands.argument("pos", BlockPosArgument.blockPos())
                                            .executes(WildsCommands::fell))))
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
                                                        "trips", DoubleArgumentType.doubleArg(0.0))
                                                    .executes(context -> route(context, 1, ""))
                                                    .then(
                                                        Commands.argument(
                                                                "width",
                                                                IntegerArgumentType.integer(1, 9))
                                                            .executes(
                                                                context ->
                                                                    route(
                                                                        context,
                                                                        IntegerArgumentType
                                                                            .getInteger(
                                                                                context, "width"),
                                                                        ""))
                                                            .then(
                                                                Commands.argument(
                                                                        "waypoints",
                                                                        StringArgumentType
                                                                            .greedyString())
                                                                    .executes(
                                                                        context ->
                                                                            route(
                                                                                context,
                                                                                IntegerArgumentType
                                                                                    .getInteger(
                                                                                        context,
                                                                                        "width"),
                                                                                StringArgumentType
                                                                                    .getString(
                                                                                        context,
                                                                                        "waypoints")))))))))
                    .then(
                        Commands.literal("simulate")
                            .then(
                                Commands.argument("days", LongArgumentType.longArg(1L, 100_000L))
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
            .min(Comparator.comparingDouble(n -> n.entrance().distSqr(pos)))
            .orElse(null);
    if (node == null) {
      context.getSource().sendFailure(Component.translatable("guest_wilds.command.lair.none"));
      return 0;
    }
    lairs.advance(level, node);
    long now = GuestTime.gameTime(level);
    MutableComponent text =
        Component.translatable(
            "guest_wilds.command.lair",
            node.entrance().toShortString(),
            node.outside == null ? "-" : node.outside.toShortString(),
            node.pos.toShortString(),
            node.depth(),
            format(node.openness),
            node.size,
            format(node.ageDays(now)),
            node.traceCount(),
            Component.translatable(
                node.ridersEstablished() ? "guest_core.common.yes" : "guest_core.common.no"));
    context.getSource().sendSuccess(() -> text.append(populations(node, true)), false);
    return 1;
  }

  private static int traces(CommandContext<CommandSourceStack> context) {
    ServerLevel level = overworld(context);
    BlockPos pos = here(context);
    Lairs.Node node =
        Lairs.get(level).nodesNear(pos, SEARCH_RADIUS).stream()
            .min(Comparator.comparingDouble(n -> n.entrance().distSqr(pos)))
            .orElse(null);
    if (node == null) {
      context.getSource().sendFailure(Component.translatable("guest_wilds.command.lair.none"));
      return 0;
    }
    for (Lairs.Trace trace : node.traces()) {
      context
          .getSource()
          .sendSuccess(
              () ->
                  Component.translatable(
                      "guest_wilds.command.lair.trace",
                      trace.pos().toShortString(),
                      trace.placed().getBlock().getName(),
                      Component.translatable(
                          "guest_wilds.species." + LairSpecies.VALUES[trace.species()].id())),
              false);
    }
    return node.traceCount();
  }

  private static int fell(CommandContext<CommandSourceStack> context) {
    ServerLevel level = overworld(context);
    BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
    Regrowth.get(level).onFelled(level, pos, level.getBlockState(pos));
    level.destroyBlock(pos, false);
    return regrowth(context);
  }

  private static MutableComponent populations(Lairs.Node node, boolean detailed) {
    MutableComponent species = Component.empty();
    for (LairSpecies s : LairSpecies.VALUES) {
      if (!detailed && node.population(s) < 0.5) {
        continue;
      }
      species
          .append(" ")
          .append(
              detailed
                  ? Component.translatable(
                      "guest_wilds.command.lair.species",
                      Component.translatable("guest_wilds.species." + s.id()),
                      format(node.population(s)),
                      node.concrete(s),
                      format(node.emigrants(s)))
                  : Component.translatable(
                      "guest_wilds.command.lairs.species",
                      Component.translatable("guest_wilds.species." + s.id()),
                      format(node.population(s))));
    }
    return species;
  }

  private static int lairs(CommandContext<CommandSourceStack> context, int radius) {
    if (!inOverworld(context)) {
      return 0;
    }
    ServerLevel level = overworld(context);
    BlockPos pos = here(context);
    Lairs lairs = Lairs.get(level);
    List<Lairs.Node> near = new ArrayList<>(lairs.nodesNear(pos, radius));
    near.sort(Comparator.comparingDouble(n -> n.entrance().distSqr(pos)));
    Lairs.Stats stats = lairs.stats;
    double millis = stats.chunks == 0 ? 0.0 : stats.nanos / 1e6 / stats.chunks;
    context
        .getSource()
        .sendSuccess(
            () ->
                Component.translatable(
                    "guest_wilds.command.lairs",
                    near.size(),
                    radius,
                    stats.chunks,
                    stats.entrances,
                    stats.lairs,
                    stats.skippedInhabited,
                    String.format(Locale.ROOT, "%.3f", millis),
                    format(lairs.residentsPerScannedChunk())),
            false);
    for (Lairs.Node node : near.subList(0, Math.min(LIST_LIMIT, near.size()))) {
      lairs.advance(level, node);
      LairSpecies dominant = node.dominant();
      BlockPos at = node.entrance();
      int dx = at.getX() - pos.getX();
      int dz = at.getZ() - pos.getZ();
      Component kind =
          dominant == null
              ? Component.translatable("guest_wilds.command.lairs.abandoned")
              : Component.translatable("guest_wilds.species." + dominant.id());
      MutableComponent line =
          Component.translatable(
              "guest_wilds.command.lairs.entry",
              kind,
              at.toShortString(),
              (int) Math.sqrt((double) dx * dx + (double) dz * dz),
              Component.translatable("guest_wilds.direction." + compass(dx, dz)));
      context.getSource().sendSuccess(() -> line.append(populations(node, false)), false);
    }
    return near.size();
  }

  private static String compass(int dx, int dz) {
    if (dx == 0 && dz == 0) {
      return "here";
    }
    String[] names = {"s", "sw", "w", "nw", "n", "ne", "e", "se"};
    double angle = Math.toDegrees(Math.atan2(-dx, dz));
    return names[(int) Math.floorMod(Math.round(angle / 45.0), 8)];
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
    BlockPos water = herd.water();
    context
        .getSource()
        .sendSuccess(
            () ->
                Component.translatable(
                    "guest_wilds.command.herd",
                    Component.translatable(
                        BuiltInRegistries.ENTITY_TYPE
                            .getValue(Identifier.parse(herd.species))
                            .getDescriptionId()),
                    format(herd.animals()),
                    herd.concrete(),
                    herd.center().getX() + ", " + herd.center().getZ(),
                    format(herds.vegetationAt(herd.center(), now)),
                    water == null ? "-" : water.toShortString(),
                    Component.translatable(
                        herd.leaderId() != null ? "guest_core.common.yes" : "guest_core.common.no"),
                    format(herds.wolves(herd.center(), 64))),
            false);
    return 1;
  }

  private static int herds(CommandContext<CommandSourceStack> context, int radius) {
    if (!inOverworld(context)) {
      return 0;
    }
    ServerLevel level = overworld(context);
    BlockPos pos = here(context);
    Herds herds = Herds.get(level);
    List<Herds.Herd> near = herds.herdsNear(pos, radius);
    context
        .getSource()
        .sendSuccess(
            () -> Component.translatable("guest_wilds.command.herds", near.size(), radius), false);
    for (Herds.Herd herd : near.subList(0, Math.min(LIST_LIMIT, near.size()))) {
      herds.advance(level, herd);
      int dx = herd.center().getX() - pos.getX();
      int dz = herd.center().getZ() - pos.getZ();
      context
          .getSource()
          .sendSuccess(
              () ->
                  Component.translatable(
                      "guest_wilds.command.herds.entry",
                      Component.translatable(
                          BuiltInRegistries.ENTITY_TYPE
                              .getValue(Identifier.parse(herd.species))
                              .getDescriptionId()),
                      format(herd.animals()),
                      herd.concrete(),
                      herd.center().getX() + ", " + herd.center().getZ(),
                      (int) Math.sqrt((double) dx * dx + (double) dz * dz),
                      Component.translatable("guest_wilds.direction." + compass(dx, dz))),
              false);
    }
    return near.size();
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
    Shoals.Spot spot = Shoals.spot(level, pos);
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
                    shoal.concrete(),
                    Component.translatable("guest_wilds.place." + spot.place().id())),
            false);
    return 1;
  }

  private static int regrowth(CommandContext<CommandSourceStack> context) {
    ServerLevel level = overworld(context);
    List<Regrowth.Site> sites = Regrowth.get(level).sites();
    BlockPos pos = here(context);
    Regrowth.Site nearest =
        sites.stream().min(Comparator.comparingDouble(s -> s.pos().distSqr(pos))).orElse(null);
    long now = GuestTime.gameTime(level);
    context
        .getSource()
        .sendSuccess(
            () ->
                Component.translatable(
                    "guest_wilds.command.regrowth",
                    sites.size(),
                    nearest == null ? "-" : nearest.pos().toShortString(),
                    nearest == null ? 0 : nearest.stage(),
                    nearest == null
                        ? "-"
                        : format((now - nearest.felled()) / (double) GuestTime.TICKS_PER_DAY)),
            false);
    long seeded = GuestWilds.SPREAD.get(level.getChunkAt(pos));
    context
        .getSource()
        .sendSuccess(
            () ->
                Component.translatable(
                    "guest_wilds.command.spread",
                    seeded == Spread.NEVER ? "-" : Spread.days(seeded),
                    Spread.days(Spread.period(now)),
                    Spread.sown()),
            false);
    return sites.size();
  }

  private static int spread(CommandContext<CommandSourceStack> context) {
    ServerLevel level = overworld(context);
    ChunkPos center = ChunkPos.containing(here(context));
    long current = Spread.period(GuestTime.gameTime(level));
    for (int dx = -8; dx <= 8; dx++) {
      for (int dz = -8; dz <= 8; dz++) {
        Spread.catchUp(level, ChunkPos.pack(center.x() + dx, center.z() + dz), current);
      }
    }
    return regrowth(context);
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

  private static int route(CommandContext<CommandSourceStack> context, int width, String spec)
      throws CommandSyntaxException {
    if (!inOverworld(context)) {
      return 0;
    }
    ServerLevel level = overworld(context);
    BlockPos from = BlockPosArgument.getBlockPos(context, "from");
    BlockPos to = BlockPosArgument.getBlockPos(context, "to");
    double trips = DoubleArgumentType.getDouble(context, "trips");
    List<BlockPos> waypoints = waypoints(level, from, to, spec.trim());
    RouteTrafficEvent.post(
        new RouteTrafficEvent(
            level,
            from,
            to,
            trips,
            Identifier.fromNamespaceAndPath(GuestWilds.MODID, "debug"),
            waypoints,
            width));
    context
        .getSource()
        .sendSuccess(
            () ->
                Component.translatable(
                    "guest_wilds.command.route", format(trips), width, waypoints.size()),
            true);
    return 1;
  }

  private static List<BlockPos> waypoints(
      ServerLevel level, BlockPos from, BlockPos to, String spec) throws CommandSyntaxException {
    if (spec.isEmpty()) {
      return List.of();
    }
    List<BlockPos> points = new ArrayList<>();
    if (spec.equals("surface")) {
      double length = Math.sqrt(from.distSqr(to.atY(from.getY())));
      int steps = Math.max(1, (int) Math.ceil(length / SURFACE_WAYPOINT_STEP));
      for (int i = 0; i <= steps; i++) {
        int x = (int) Math.round(from.getX() + (to.getX() - from.getX()) * i / (double) steps);
        int z = (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * i / (double) steps);
        points.add(
            new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z));
      }
      return points;
    }
    for (String part : spec.split(";")) {
      String[] xyz = part.trim().split("\\s+");
      if (xyz.length != 3) {
        throw BAD_WAYPOINTS.create();
      }
      try {
        points.add(
            new BlockPos(
                Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])));
      } catch (NumberFormatException e) {
        throw BAD_WAYPOINTS.create();
      }
    }
    return points;
  }

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
    lairs.nodes().forEach(node -> lairs.refresh(level, node));
    Herds herds = Herds.get(level);
    herds.herds().forEach(herd -> herds.advance(level, herd));
    PathWear.get(level).refreshAll(level);
    Regrowth.get(level).advanceAll(level);
    context
        .getSource()
        .sendSuccess(() -> Component.translatable("guest_wilds.command.simulate", days), true);
    return 1;
  }

  private static String format(double value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }
}
