package com.vortexso.guest_settlements.life;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_settlements.GuestSettlements;
import com.vortexso.guest_settlements.village.VillageNode;
import com.vortexso.guest_settlements.village.VillagePopulationScanner;
import com.vortexso.guest_settlements.village.VillageWorldManager;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = GuestSettlements.MODID)
public final class LifeCommands {
  private LifeCommands() {}

  @SubscribeEvent
  public static void register(RegisterCommandsEvent event) {
    event
        .getDispatcher()
        .register(
            Commands.literal("guest")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("settlements")
                        .then(
                            Commands.literal("life")
                                .executes(LifeCommands::life)
                                .then(Commands.literal("aurora").executes(LifeCommands::aurora)))
                        .then(
                            Commands.literal("build")
                                .executes(context -> plan(context, null))
                                .then(Commands.literal("cancel").executes(LifeCommands::cancel))
                                .then(
                                    Commands.literal("advance")
                                        .then(
                                            Commands.argument(
                                                    "blocks", IntegerArgumentType.integer(1, 10000))
                                                .executes(LifeCommands::advance)))
                                .then(
                                    Commands.argument("kind", StringArgumentType.word())
                                        .suggests(
                                            (context, builder) ->
                                                SharedSuggestionProvider.suggest(
                                                    java.util.Arrays.stream(
                                                            Construction.Kind.values())
                                                        .map(
                                                            kind ->
                                                                kind.name()
                                                                    .toLowerCase(Locale.ROOT)),
                                                    builder))
                                        .executes(
                                            context ->
                                                plan(
                                                    context,
                                                    Construction.Kind.valueOf(
                                                        StringArgumentType.getString(
                                                                context, "kind")
                                                            .toUpperCase(Locale.ROOT))))))));
  }

  private static int life(CommandContext<CommandSourceStack> context) {
    ServerLevel level = context.getSource().getLevel();
    VillageNode node = nearest(context);
    if (node == null || node.structureBox() == null) {
      return 0;
    }
    long clock = GuestTime.gameTime(level);
    send(
        context,
        Component.translatable(
            "guest_settlements.command.life",
            node.center().toShortString(),
            GuestTime.tickOfDay(clock)));
    for (Villager villager : VillagePopulationScanner.findVillagers(level, node.structureBox())) {
      Errands.Errand errand = Errands.current(villager);
      Errands.Step step = errand == null ? null : errand.step();
      send(
          context,
          Component.translatable(
              "guest_settlements.command.life.villager",
              villager.getDisplayName(),
              errand == null
                  ? Component.translatable(
                      "guest_settlements.command.life.vanilla",
                      villager
                          .getBrain()
                          .getActiveNonCoreActivity()
                          .map(Object::toString)
                          .orElse("-"))
                  : Component.translatable(
                      "guest_settlements.debug.role."
                          + errand.role().name().toLowerCase(Locale.ROOT)),
              step == null || step.where() == null
                  ? "-"
                  : step.where().currentBlockPosition().toShortString(),
              villager.getMainHandItem().getHoverName(),
              villager.getBrain().getActiveNonCoreActivity().map(Object::toString).orElse("-"),
              villager
                  .getBrain()
                  .getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET)
                  .map(walk -> walk.getTarget().currentBlockPosition().toShortString())
                  .orElse("-")));
      if (villager.getVillagerData().profession().is(VillagerProfession.CARTOGRAPHER)) {
        Cartographers.Trip trip =
            Cartographers.trip(level.getSeed(), villager.getUUID(), GuestTime.day(clock));
        send(
            context,
            Component.translatable(
                "guest_settlements.command.life.trip",
                trip == null ? "-" : trip.depart(),
                trip == null ? "-" : trip.back()));
      }
    }
    for (LifeData.Traveler traveler : LifeData.get(level).travelers(node.id())) {
      send(
          context,
          Component.translatable(
              "guest_settlements.command.life.away",
              traveler.depart(),
              traveler.back(),
              traveler.gate().toShortString()));
    }
    for (Construction.View view : Construction.views(level, node)) {
      send(
          context,
          Component.translatable(
              "guest_settlements.command.life.project",
              Component.translatable(
                  "guest_settlements.project." + view.kind().toLowerCase(Locale.ROOT)),
              view.name(),
              view.placed(),
              view.total(),
              view.wood(),
              view.stone(),
              view.next() == null ? "-" : view.next().toShortString()));
    }
    return 1;
  }

  private static int aurora(CommandContext<CommandSourceStack> context) {
    VillageNode node = nearest(context);
    if (node == null) {
      return 0;
    }
    Rites.forceAurora(context.getSource().getLevel(), node);
    send(context, Component.translatable("guest_settlements.command.life.aurora"));
    return 1;
  }

  private static int plan(CommandContext<CommandSourceStack> context, Construction.Kind kind) {
    ServerLevel level = context.getSource().getLevel();
    VillageNode node = nearest(context);
    if (node == null) {
      return 0;
    }
    String result = Construction.planNow(level, VillageWorldManager.get(level), node, kind);
    send(
        context,
        result == null
            ? Component.translatable("guest_settlements.command.build.none")
            : Component.translatable("guest_settlements.command.build.planned", result));
    return result == null ? 0 : 1;
  }

  private static int cancel(CommandContext<CommandSourceStack> context) {
    ServerLevel level = context.getSource().getLevel();
    VillageNode node = nearest(context);
    int removed = node == null ? 0 : Construction.cancel(level, node);
    send(context, Component.translatable("guest_settlements.command.build.cancelled", removed));
    return removed;
  }

  private static int advance(CommandContext<CommandSourceStack> context) {
    ServerLevel level = context.getSource().getLevel();
    VillageNode node = nearest(context);
    if (node == null) {
      return 0;
    }
    int placed =
        Construction.advance(level, node, IntegerArgumentType.getInteger(context, "blocks"));
    send(context, Component.translatable("guest_settlements.command.build.advanced", placed));
    return placed;
  }

  private static VillageNode nearest(CommandContext<CommandSourceStack> context) {
    VillageNode node =
        VillageWorldManager.get(context.getSource().getLevel())
            .nearest(BlockPos.containing(context.getSource().getPosition()));
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
}
