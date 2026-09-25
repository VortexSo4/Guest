package com.vortexso.guest_architects.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.vortexso.guest_architects.ArchitectsConfig;
import com.vortexso.guest_architects.GuestArchitects;
import com.vortexso.guest_architects.city.ArchitectsData;
import com.vortexso.guest_architects.city.CityLife;
import com.vortexso.guest_architects.city.CityManager;
import com.vortexso.guest_architects.city.CitySite;
import com.vortexso.guest_core.api.GuestTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** {@code /guest architects ...}: inspection and manipulation of the nearest known city. */
@EventBusSubscriber(modid = GuestArchitects.MODID)
public final class ArchitectsCommands {
  private static final String AUTO = "auto";

  private ArchitectsCommands() {}

  @SubscribeEvent
  public static void register(RegisterCommandsEvent event) {
    event
        .getDispatcher()
        .register(
            Commands.literal("guest")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("architects")
                        .then(Commands.literal("list").executes(ArchitectsCommands::list))
                        .then(Commands.literal("info").executes(ArchitectsCommands::info))
                        .then(
                            Commands.literal("living")
                                .then(
                                    Commands.argument("living", BoolArgumentType.bool())
                                        .executes(ArchitectsCommands::living))
                                .then(
                                    Commands.literal(AUTO)
                                        .executes(ArchitectsCommands::livingAuto)))
                        .then(
                            Commands.literal("state")
                                .then(
                                    Commands.argument("state", StringArgumentType.word())
                                        .suggests(
                                            (context, builder) ->
                                                SharedSuggestionProvider.suggest(
                                                    stateNames(), builder))
                                        .executes(ArchitectsCommands::state)))
                        .then(
                            Commands.literal("materialize")
                                .executes(ArchitectsCommands::materialize))
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
    }
    return Optional.ofNullable(site);
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
      BlockPos c = site.center();
      context
          .getSource()
          .sendSuccess(
              () ->
                  Component.translatable(
                      "guest_architects.command.list_entry",
                      site.id,
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
              CityLife.Params params = ArchitectsConfig.lifeParams();
              CityLife.Profile profile = CityManager.get(level).profile(site, params);
              double day = CityLife.day(GuestTime.gameTime(level));
              double abandoned = CityLife.abandonedSince(profile, site.record.delayDays, params);
              Component abandonedText =
                  Double.isNaN(abandoned)
                      ? Component.translatable("guest_architects.command.never")
                      : Component.literal(String.format(Locale.ROOT, "%.1f", abandoned));
              context
                  .getSource()
                  .sendSuccess(
                      () ->
                          Component.translatable(
                              "guest_architects.command.info",
                              site.id,
                              Component.translatable(
                                  profile.living()
                                      ? "guest_architects.debug.living"
                                      : "guest_architects.debug.abandoned"),
                              stateName(profile.state()),
                              profile.basePopulation(),
                              CityLife.population(profile, day, site.record.delayDays, params),
                              String.format(Locale.ROOT, "%.2f", site.record.delayDays),
                              abandonedText,
                              site.record.spreadApplied,
                              site.record.nichesPlaced,
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

  private static int living(CommandContext<CommandSourceStack> context) {
    boolean living = BoolArgumentType.getBool(context, "living");
    return change(context, site -> site.record.livingOverride = Optional.of(living));
  }

  private static int livingAuto(CommandContext<CommandSourceStack> context) {
    return change(context, site -> site.record.livingOverride = Optional.empty());
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

  /** Forgets player relations and the recognition moment; the world changes already made stay. */
  private static int reset(CommandContext<CommandSourceStack> context) {
    return change(
        context,
        site -> {
          site.record.livingOverride = Optional.empty();
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
