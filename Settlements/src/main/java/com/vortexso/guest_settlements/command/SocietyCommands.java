package com.vortexso.guest_settlements.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_settlements.GuestSettlements;
import com.vortexso.guest_settlements.illager.IllagerCamps;
import com.vortexso.guest_settlements.illager.IllagerRhythm;
import com.vortexso.guest_settlements.memory.VillageMemory;
import com.vortexso.guest_settlements.society.SocietyData;
import com.vortexso.guest_settlements.village.Emigration;
import com.vortexso.guest_settlements.village.RoadEdge;
import com.vortexso.guest_settlements.village.Roads;
import com.vortexso.guest_settlements.village.RoutePlanner;
import com.vortexso.guest_settlements.village.VillageNode;
import com.vortexso.guest_settlements.village.VillageSimulator;
import com.vortexso.guest_settlements.village.VillageWorldManager;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = GuestSettlements.MODID)
public final class SocietyCommands {
  private static final double ROAD_RADIUS = 2048.0;
  private static final double CAMP_RADIUS = 4096.0;
  private static final double TRACE_RADIUS = 256.0;

  private SocietyCommands() {}

  @SubscribeEvent
  public static void register(RegisterCommandsEvent event) {
    event
        .getDispatcher()
        .register(
            Commands.literal("guest")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("settlements")
                        .then(Commands.literal("roads").executes(SocietyCommands::roads))
                        .then(Commands.literal("camps").executes(SocietyCommands::camps))
                        .then(Commands.literal("ritual").executes(SocietyCommands::ritual))
                        .then(
                            Commands.literal("emigration")
                                .executes(context -> emigration(context, false))
                                .then(
                                    Commands.literal("now")
                                        .executes(context -> emigration(context, true))))
                        .then(Commands.literal("tent").executes(SocietyCommands::tent))
                        .then(
                            Commands.literal("fame")
                                .then(
                                    Commands.argument("kills", IntegerArgumentType.integer(1, 1000))
                                        .executes(SocietyCommands::fame)))
                        .then(Commands.literal("traces").executes(SocietyCommands::traces))));
  }

  private static int roads(CommandContext<CommandSourceStack> context) {
    ServerLevel level = context.getSource().getLevel();
    VillageWorldManager manager = VillageWorldManager.get(level);
    BlockPos pos = BlockPos.containing(context.getSource().getPosition());
    int count = 0;
    for (RoadEdge edge : List.copyOf(manager.roads())) {
      VillageNode first = manager.node(edge.firstVillageId());
      VillageNode second = manager.node(edge.secondVillageId());
      if (first == null
          || second == null
          || Math.min(first.center().distSqr(pos), second.center().distSqr(pos))
              > ROAD_RADIUS * ROAD_RADIUS) {
        continue;
      }
      long started = System.nanoTime();
      RoutePlanner.Plan plan =
          RoutePlanner.replan(level, Roads.anchor(first), Roads.anchor(second));
      double millis = (System.nanoTime() - started) / 1_000_000.0;
      List<BlockPos> route = plan.waypoints();
      send(
          context,
          Component.translatable(
              plan.straight()
                  ? "guest_settlements.command.roads.straight"
                  : "guest_settlements.command.roads.entry",
              first.center().toShortString(),
              second.center().toShortString(),
              format(Math.sqrt(first.center().distSqr(second.center()))),
              climb(straightProfile(level, route.getFirst(), route.getLast())),
              format(Roads.length(route)),
              climb(route),
              format(steepest(route)),
              route.size(),
              plan.samples(),
              plan.expanded(),
              format(millis)));
      StringBuilder points = new StringBuilder();
      for (BlockPos point : route) {
        points
            .append(' ')
            .append(point.getX())
            .append(',')
            .append(point.getY())
            .append(',')
            .append(point.getZ());
      }
      send(context, Component.literal(points.toString()));
      count++;
    }
    send(context, Component.translatable("guest_settlements.command.roads", count));
    return count;
  }

  private static List<BlockPos> straightProfile(ServerLevel level, BlockPos from, BlockPos to) {
    RoutePlanner.Terrain terrain = RoutePlanner.terrain(level);
    int steps = (int) (Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ()) / 16.0);
    List<BlockPos> result = new java.util.ArrayList<>();
    result.add(from);
    for (int s = 1; s < steps; s++) {
      int x = from.getX() + (to.getX() - from.getX()) * s / steps;
      int z = from.getZ() + (to.getZ() - from.getZ()) * s / steps;
      result.add(new BlockPos(x, terrain.surface(x, z), z));
    }
    result.add(to);
    return result;
  }

  private static int climb(List<BlockPos> route) {
    int climb = 0;
    for (int k = 1; k + 2 < route.size(); k++) {
      climb += Math.abs(route.get(k + 1).getY() - route.get(k).getY());
    }
    return climb;
  }

  private static double steepest(List<BlockPos> route) {
    double steepest = 0.0;
    for (int k = 1; k + 2 < route.size(); k++) {
      BlockPos a = route.get(k);
      BlockPos b = route.get(k + 1);
      double run = Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
      if (run > 0.0) {
        steepest = Math.max(steepest, Math.abs(b.getY() - a.getY()) / run);
      }
    }
    return steepest;
  }

  private static int camps(CommandContext<CommandSourceStack> context) {
    ServerLevel level = context.getSource().getLevel();
    BlockPos pos = BlockPos.containing(context.getSource().getPosition());
    List<SocietyData.Camp> camps =
        SocietyData.get(level).camps.values().stream()
            .filter(camp -> camp.center().distSqr(pos) < CAMP_RADIUS * CAMP_RADIUS)
            .sorted(Comparator.comparingDouble(camp -> camp.center().distSqr(pos)))
            .toList();
    send(context, Component.translatable("guest_settlements.command.camps", camps.size()));
    long today = GuestTime.day(GuestTime.gameTime(level));
    for (SocietyData.Camp camp : camps) {
      int prestige = IllagerCamps.prestige(level, camp);
      send(
          context,
          Component.translatable(
              "guest_settlements.command.camps.entry",
              Component.translatable("guest_settlements.camp." + camp.kind().getSerializedName()),
              camp.center().toShortString(),
              camp.evokers(),
              Component.translatable(
                  IllagerCamps.illusionist(level, camp)
                      ? "entity.minecraft.illusioner"
                      : "entity.minecraft.evoker"),
              camp.ravagers(),
              camp.pending(),
              camp.crew(),
              prestige,
              IllagerCamps.leaderEpoch(level, camp),
              yesNo(camp.day() < today && IllagerCamps.ritualDue(level, camp, today, prestige)),
              camp.pen().map(BlockPos::toShortString).orElse("-")));
    }
    return camps.size();
  }

  private static int ritual(CommandContext<CommandSourceStack> context) {
    ServerLevel level = context.getSource().getLevel();
    BlockPos pos = BlockPos.containing(context.getSource().getPosition());
    SocietyData.Camp camp =
        SocietyData.get(level).camps.values().stream()
            .min(Comparator.comparingDouble(c -> c.center().distSqr(pos)))
            .orElse(null);
    if (camp == null) {
      context.getSource().sendFailure(Component.translatable("guest_settlements.command.no_camp"));
      return 0;
    }
    IllagerCamps.forceRitual(level, camp);
    send(
        context,
        Component.translatable("guest_settlements.command.ritual", camp.center().toShortString()));
    return 1;
  }

  private static int emigration(CommandContext<CommandSourceStack> context, boolean now) {
    ServerLevel level = context.getSource().getLevel();
    VillageWorldManager manager = VillageWorldManager.get(level);
    VillageNode node = manager.nearest(BlockPos.containing(context.getSource().getPosition()));
    if (node == null) {
      context
          .getSource()
          .sendFailure(Component.translatable("guest_settlements.command.no_village"));
      return 0;
    }
    SocietyData data = SocietyData.get(level);
    VillageNode target = Emigration.destination(manager, node, data);
    send(
        context,
        Component.translatable(
            "guest_settlements.command.emigration",
            node.center().toShortString(),
            node.state().villagePopulation().professionCount(VillageSimulator.NONE),
            data.immigrants(node.id()),
            target == null ? "-" : target.center().toShortString(),
            target == null ? 0 : data.immigrants(target.id())));
    if (now) {
      boolean started = Emigration.forceWalkOut(level, manager, node);
      send(
          context,
          Component.translatable(
              started
                  ? "guest_settlements.command.emigration.started"
                  : "guest_settlements.command.emigration.none"));
    }
    return 1;
  }

  private static int tent(CommandContext<CommandSourceStack> context)
      throws com.mojang.brigadier.exceptions.CommandSyntaxException {
    ServerPlayer player = context.getSource().getPlayerOrException();
    ServerLevel level = player.level();
    if (IllagerRhythm.strike(level, player)) {
      send(context, Component.translatable("guest_settlements.command.tent.struck"));
      return 1;
    }
    VillageNode node = VillageWorldManager.get(level).nearest(player.blockPosition());
    if (node == null) {
      context
          .getSource()
          .sendFailure(Component.translatable("guest_settlements.command.no_village"));
      return 0;
    }
    if (!IllagerRhythm.pitch(level, player, node.center(), true)) {
      context
          .getSource()
          .sendFailure(Component.translatable("guest_settlements.command.tent.none"));
      return 0;
    }
    send(
        context,
        Component.translatable(
            "guest_settlements.command.tent.pitched", node.center().toShortString()));
    return 1;
  }

  private static int fame(CommandContext<CommandSourceStack> context)
      throws com.mojang.brigadier.exceptions.CommandSyntaxException {
    ServerPlayer player = context.getSource().getPlayerOrException();
    int kills = IntegerArgumentType.getInteger(context, "kills");
    VillageMemory.recordIds(
        player.level(),
        player.blockPosition(),
        VillageMemory.ILLAGER_KILLED,
        player.getUUID(),
        null,
        kills);
    send(context, Component.translatable("guest_settlements.command.fame", kills));
    return kills;
  }

  private static int traces(CommandContext<CommandSourceStack> context) {
    ServerLevel level = context.getSource().getLevel();
    BlockPos pos = BlockPos.containing(context.getSource().getPosition());
    long now = GuestTime.gameTime(level);
    int count = 0;
    for (SocietyData.Trace trace : List.copyOf(SocietyData.get(level).traces.values())) {
      if (trace.blocks().isEmpty()
          || trace.blocks().getFirst().pos().distSqr(pos) > TRACE_RADIUS * TRACE_RADIUS) {
        continue;
      }
      count++;
      send(
          context,
          Component.translatable(
              "guest_settlements.command.traces.entry",
              Component.translatable("guest_settlements.trace." + trace.kind()),
              trace.blocks().getFirst().pos().toShortString(),
              trace.blocks().size(),
              (now - trace.time()) / GuestTime.TICKS_PER_DAY,
              trace.lifetime() < 0
                  ? "-"
                  : String.valueOf(
                      (trace.time() + trace.lifetime() - now) / GuestTime.TICKS_PER_DAY)));
    }
    send(context, Component.translatable("guest_settlements.command.traces", count));
    return count;
  }

  private static Component yesNo(boolean value) {
    return Component.translatable(value ? "guest_core.common.yes" : "guest_core.common.no");
  }

  static void send(CommandContext<CommandSourceStack> context, Component message) {
    context.getSource().sendSuccess(() -> message, false);
  }

  static String format(double value) {
    return String.format(Locale.ROOT, "%.1f", value);
  }
}
