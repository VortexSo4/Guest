package com.vortexso.guest_architects.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.vortexso.guest_architects.ArchitectsConfig;
import com.vortexso.guest_architects.city.ArchitectsData;
import com.vortexso.guest_architects.city.CityLife;
import com.vortexso.guest_architects.city.CityManager;
import com.vortexso.guest_architects.city.CityRelations;
import com.vortexso.guest_architects.city.CitySite;
import com.vortexso.guest_architects.city.MemoryPlace;
import com.vortexso.guest_core.api.GuestTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class ArchitectsCommands {
  private static final String AUTO = "auto";

  private ArchitectsCommands() {}

  public static void register(
      CommandDispatcher<CommandSourceStack> dispatcher,
      CommandBuildContext buildContext,
      Commands.CommandSelection selection) {
    dispatcher.register(
        Commands.literal("guest")
            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .then(
                Commands.literal("architects")
                    .then(Commands.literal("list").executes(ArchitectsCommands::list))
                    .then(Commands.literal("info").executes(ArchitectsCommands::info))
                    .then(Commands.literal("extinct").executes(ArchitectsCommands::extinct))
                    .then(
                        Commands.literal("provoke")
                            .then(
                                Commands.argument("points", DoubleArgumentType.doubleArg(0.0))
                                    .executes(ArchitectsCommands::provoke)))
                    .then(
                        Commands.literal("state")
                            .then(
                                Commands.argument("state", StringArgumentType.word())
                                    .suggests(
                                        (context, builder) ->
                                            SharedSuggestionProvider.suggest(stateNames(), builder))
                                    .executes(ArchitectsCommands::state)))
                    .then(Commands.literal("materialize").executes(ArchitectsCommands::materialize))
                    .then(Commands.literal("reset").executes(ArchitectsCommands::reset))));
  }

  private static String[] stateNames() {
    String[] names =
        Arrays.stream(CityLife.State.values())
            .map(s -> s.name().toLowerCase(Locale.ROOT))
            .toArray(String[]::new);
    String[] all = Arrays.copyOf(names, names.length + 1);
    all[names.length] = AUTO;
    return all;
  }

  private static Optional<CitySite> nearest(CommandContext<CommandSourceStack> context) {
    ServerLevel level = context.getSource().getLevel();
    CitySite site =
        CityManager.get(level).nearest(BlockPos.containing(context.getSource().getPosition()));
    if (site == null) {
      context.getSource().sendFailure(Component.translatable("guest_architects.command.no_city"));
    } else {
      CityManager.get(level).refresh(site);
    }
    return Optional.ofNullable(site);
  }

  private static Component kind(CitySite site) {
    return Component.translatable(
        !site.livingStructure
            ? "guest_architects.debug.abandoned"
            : site.population() > 0
                ? "guest_architects.debug.living"
                : "guest_architects.debug.extinct");
  }

  private static int list(CommandContext<CommandSourceStack> context) {
    ServerLevel level = context.getSource().getLevel();
    BlockPos from = BlockPos.containing(context.getSource().getPosition());
    var sites = CityManager.get(level).sites();
    if (sites.isEmpty()) {
      context.getSource().sendFailure(Component.translatable("guest_architects.command.no_city"));
      return 0;
    }
    for (CitySite site : sites) {
      CityManager.get(level).refresh(site);
      BlockPos c = site.center();
      context
          .getSource()
          .sendSuccess(
              () ->
                  Component.translatable(
                      "guest_architects.command.list_entry",
                      site.id,
                      kind(site),
                      c.getX(),
                      c.getY(),
                      c.getZ(),
                      (int) Math.sqrt(c.distSqr(from)),
                      site.population()),
              false);
    }
    return sites.size();
  }

  private static int info(CommandContext<CommandSourceStack> context) {
    return nearest(context)
        .map(
            site -> {
              ServerLevel level = context.getSource().getLevel();
              CityManager manager = CityManager.get(level);
              CityLife.Params params = ArchitectsConfig.lifeParams();
              CityLife.Profile profile = manager.profile(site, params);
              double abandoned = manager.abandonedSince(site, params);
              Component abandonedText =
                  Double.isNaN(abandoned)
                      ? Component.translatable("guest_architects.command.never")
                      : Component.literal(String.format(Locale.ROOT, "%.1f", abandoned));
              MemoryPlace.Markers memory = site.memory();
              Component memoryText =
                  memory == null
                      ? Component.translatable("guest_core.common.no")
                      : Component.translatable(
                          "guest_architects.command.memory_at",
                          Component.translatable(
                              "guest_architects.relic."
                                  + MemoryPlace.relic(level.getSeed(), site.id)
                                      .name()
                                      .toLowerCase(Locale.ROOT)),
                          memory.keeper().getX(),
                          memory.keeper().getY(),
                          memory.keeper().getZ());
              context
                  .getSource()
                  .sendSuccess(
                      () ->
                          Component.translatable(
                              "guest_architects.command.info",
                              site.id,
                              kind(site),
                              stateName(profile.state()),
                              profile.basePopulation(),
                              site.population(),
                              String.format(Locale.ROOT, "%.2f", site.record.delayDays),
                              abandonedText,
                              site.record.spreadApplied,
                              site.ritualBoxes.size(),
                              memoryText,
                              site.record.converted.size(),
                              site.pieces.size(),
                              Component.translatable(
                                  site.record.recognitionUsed
                                      ? "guest_core.common.yes"
                                      : "guest_core.common.no"),
                              site.record.damage.size(),
                              site.record.relations.size()),
                      false);
              return 1;
            })
        .orElse(0);
  }

  private static Component stateName(CityLife.State state) {
    return Component.translatable(
        "guest_architects.state." + state.name().toLowerCase(Locale.ROOT));
  }

  private static int extinct(CommandContext<CommandSourceStack> context) {
    ServerLevel level = context.getSource().getLevel();
    Optional<CitySite> site = nearest(context);
    if (site.isPresent() && !site.get().livingStructure) {
      context
          .getSource()
          .sendFailure(Component.translatable("guest_architects.command.not_living"));
      return 0;
    }
    double day = CityLife.day(GuestTime.gameTime(level));
    return change(context, s -> s.record.extinctDay = Optional.of(s.record.extinctDay.orElse(day)));
  }

  private static int provoke(CommandContext<CommandSourceStack> context)
      throws CommandSyntaxException {
    ServerPlayer player = context.getSource().getPlayerOrException();
    double points = DoubleArgumentType.getDouble(context, "points");
    return nearest(context)
        .map(
            site -> {
              boolean witnessed =
                  CityRelations.provoke(context.getSource().getLevel(), site, player, points);
              if (!witnessed) {
                context
                    .getSource()
                    .sendFailure(Component.translatable("guest_architects.command.no_witness"));
                return 0;
              }
              context
                  .getSource()
                  .sendSuccess(
                      () ->
                          Component.translatable(
                              "guest_architects.command.provoked", site.id, points),
                      false);
              return 1;
            })
        .orElse(0);
  }

  private static int state(CommandContext<CommandSourceStack> context) {
    String name = StringArgumentType.getString(context, "state");
    if (AUTO.equals(name)) {
      return change(context, site -> site.record.stateOverride = Optional.empty());
    }
    CityLife.State state;
    try {
      state = CityLife.State.valueOf(name.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      context
          .getSource()
          .sendFailure(Component.translatable("guest_architects.command.unknown_state", name));
      return 0;
    }
    return change(context, site -> site.record.stateOverride = Optional.of(state));
  }

  private static int materialize(CommandContext<CommandSourceStack> context) {
    return change(context, site -> {});
  }

  private static int reset(CommandContext<CommandSourceStack> context) {
    return change(
        context,
        site -> {
          site.record.stateOverride = Optional.empty();
          site.record.relations.clear();
          site.record.recognitionUsed = false;
          site.record.delayDays = 0;
          site.record.lastGrantDay = 0;
        });
  }

  private static int change(
      CommandContext<CommandSourceStack> context, java.util.function.Consumer<CitySite> change) {
    return nearest(context)
        .map(
            site -> {
              ServerLevel level = context.getSource().getLevel();
              change.accept(site);
              ArchitectsData.get(level).setDirty();
              CityManager.get(level).invalidate(site);
              context
                  .getSource()
                  .sendSuccess(
                      () -> Component.translatable("guest_architects.command.updated", site.id),
                      true);
              return 1;
            })
        .orElse(0);
  }
}
