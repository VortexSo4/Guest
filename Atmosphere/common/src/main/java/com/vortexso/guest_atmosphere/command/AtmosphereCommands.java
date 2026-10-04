package com.vortexso.guest_atmosphere.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.vortexso.guest_atmosphere.AtmosphereConfig;
import com.vortexso.guest_atmosphere.GuestAtmosphere;
import com.vortexso.guest_atmosphere.trace.ChunkTraces;
import com.vortexso.guest_atmosphere.trace.TraceSimulator;
import com.vortexso.guest_atmosphere.weather.AtmosphereWeather;
import com.vortexso.guest_atmosphere.weather.WeatherModel;
import com.vortexso.guest_atmosphere.weather.WeatherModel.Sample;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.Season;
import com.vortexso.guest_core.api.world.WeatherType;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Stream;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

public final class AtmosphereCommands {
  private static final int TICKS_PER_MINUTE = 1_200;
  private static final String AURORA = "aurora";

  private static final long YEAR_SAMPLE_TICKS = 1_500L;

  private AtmosphereCommands() {}

  public static void register(
      CommandDispatcher<CommandSourceStack> dispatcher,
      CommandBuildContext buildContext,
      Commands.CommandSelection selection) {
    dispatcher.register(
        Commands.literal("guest")
            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .then(
                Commands.literal("atmosphere")
                    .then(Commands.literal("here").executes(AtmosphereCommands::here))
                    .then(Commands.literal("year").executes(AtmosphereCommands::year))
                    .then(
                        Commands.literal("forecast")
                            .executes(context -> forecast(context, 8))
                            .then(
                                Commands.argument("segments", IntegerArgumentType.integer(1, 64))
                                    .executes(
                                        context ->
                                            forecast(
                                                context,
                                                IntegerArgumentType.getInteger(
                                                    context, "segments")))))
                    .then(
                        Commands.literal("force")
                            .then(
                                Commands.argument("type", StringArgumentType.word())
                                    .suggests(
                                        (context, builder) ->
                                            SharedSuggestionProvider.suggest(
                                                Stream.concat(
                                                    Arrays.stream(WeatherType.values())
                                                        .map(
                                                            type ->
                                                                type.name()
                                                                    .toLowerCase(Locale.ROOT)),
                                                    Stream.of(AURORA)),
                                                builder))
                                    .executes(context -> force(context, 1.0F, 10))
                                    .then(
                                        Commands.argument(
                                                "intensity", FloatArgumentType.floatArg(0.0F, 1.0F))
                                            .executes(
                                                context ->
                                                    force(
                                                        context,
                                                        FloatArgumentType.getFloat(
                                                            context, "intensity"),
                                                        10))
                                            .then(
                                                Commands.argument(
                                                        "minutes",
                                                        IntegerArgumentType.integer(1, 1_440))
                                                    .executes(
                                                        context ->
                                                            force(
                                                                context,
                                                                FloatArgumentType.getFloat(
                                                                    context, "intensity"),
                                                                IntegerArgumentType.getInteger(
                                                                    context, "minutes")))))))
                    .then(Commands.literal("unforce").executes(AtmosphereCommands::unforce))
                    .then(
                        Commands.literal("traces")
                            .executes(context -> traces(context, 1))
                            .then(
                                Commands.argument("radius", IntegerArgumentType.integer(0, 8))
                                    .executes(
                                        context ->
                                            traces(
                                                context,
                                                IntegerArgumentType.getInteger(
                                                    context, "radius")))))
                    .then(
                        Commands.literal("catchup")
                            .executes(context -> catchUp(context, 1))
                            .then(
                                Commands.argument("radius", IntegerArgumentType.integer(0, 4))
                                    .executes(
                                        context ->
                                            catchUp(
                                                context,
                                                IntegerArgumentType.getInteger(
                                                    context, "radius")))))));
  }

  private static int here(CommandContext<CommandSourceStack> context) {
    ServerLevel level = overworld(context);
    if (level == null) {
      return 0;
    }
    BlockPos pos = BlockPos.containing(context.getSource().getPosition());
    long time = GuestTime.gameTime(level);
    Sample sample = AtmosphereWeather.sample(level, pos, time);
    WeatherModel.Climate climate = AtmosphereWeather.climate(level, pos);
    int size = AtmosphereConfig.weather().cellSize();
    long cellX =
        (long)
            Math.floor((pos.getX() - WeatherModel.drift(time, AtmosphereConfig.weather())) / size);
    long cellZ = Math.floorDiv(pos.getZ(), size);
    context
        .getSource()
        .sendSuccess(
            () ->
                Component.translatable(
                    "guest_atmosphere.command.here",
                    sample.state().type().displayName(),
                    format(sample.state().intensity()),
                    format(sample.state().wind()),
                    format(sample.temperature()),
                    Component.translatable(
                        sample.state().aurora() ? "guest_core.common.yes" : "guest_core.common.no"),
                    Component.translatable(
                        "guest_atmosphere.climate."
                            + climate.climateClass().name().toLowerCase(Locale.ROOT)),
                    cellX,
                    cellZ),
            false);

    context
        .getSource()
        .sendSuccess(
            () ->
                Component.translatable(
                    "guest_atmosphere.command.here.calendar",
                    GuestTime.year(time),
                    GuestTime.season(time).displayName(),
                    GuestTime.dayOfYear(time) % GuestTime.DAYS_PER_SEASON + 1,
                    GuestTime.DAYS_PER_SEASON,
                    GuestTime.day(time) + 1),
            false);
    WeatherModel.Cover cover = AtmosphereWeather.cover(level, pos, time);
    context
        .getSource()
        .sendSuccess(
            () ->
                Component.translatable(
                    "guest_atmosphere.command.here.cover",
                    format(cover.snow()),
                    format(cover.dryness()),
                    format(climate.dampness())),
            false);
    return 1;
  }

  private static int year(CommandContext<CommandSourceStack> context) {
    ServerLevel level = overworld(context);
    if (level == null) {
      return 0;
    }
    BlockPos pos = BlockPos.containing(context.getSource().getPosition());
    WeatherModel.Climate climate = AtmosphereWeather.climate(level, pos);
    long yearStart =
        GuestTime.year(GuestTime.gameTime(level))
            * GuestTime.DAYS_PER_YEAR
            * GuestTime.TICKS_PER_DAY;
    long seasonTicks = GuestTime.DAYS_PER_SEASON * GuestTime.TICKS_PER_DAY;
    for (Season season : Season.values()) {
      int[] counts = new int[6];
      int samples = 0;
      for (long t = 0; t < seasonTicks; t += YEAR_SAMPLE_TICKS, samples++) {
        WeatherType type =
            WeatherModel.sample(
                    level.getSeed(),
                    yearStart + season.ordinal() * seasonTicks + t,
                    pos.getX(),
                    pos.getZ(),
                    climate,
                    AtmosphereConfig.weather())
                .state()
                .type();
        int bucket =
            type.isSevere()
                ? 2
                : type.isRain()
                    ? 0
                    : type.isSnow()
                        ? 1
                        : type == WeatherType.FOG ? 3 : type == WeatherType.HEAT ? 4 : 5;
        counts[bucket]++;
      }
      int total = samples;
      context
          .getSource()
          .sendSuccess(
              () ->
                  Component.translatable(
                      "guest_atmosphere.command.year",
                      season.displayName(),
                      percent(counts[0], total),
                      percent(counts[1], total),
                      percent(counts[2], total),
                      percent(counts[3], total),
                      percent(counts[4], total),
                      percent(counts[5], total)),
              false);
    }
    return 1;
  }

  private static int forecast(CommandContext<CommandSourceStack> context, int segments) {
    ServerLevel level = overworld(context);
    if (level == null) {
      return 0;
    }
    BlockPos pos = BlockPos.containing(context.getSource().getPosition());
    long segment = AtmosphereConfig.weather().segmentTicks();
    long first = Math.floorDiv(GuestTime.gameTime(level), segment);
    for (int i = 0; i < segments; i++) {
      long time = (first + i) * segment + segment / 2;
      Sample sample = AtmosphereWeather.sample(level, pos, time);
      long tick = GuestTime.tickOfDay(time);

      String clock =
          String.format(
              Locale.ROOT, "%02d:%02d", (tick / 1_000 + 6) % 24, tick % 1_000 * 60 / 1_000);
      context
          .getSource()
          .sendSuccess(
              () ->
                  Component.translatable(
                      "guest_atmosphere.command.forecast",
                      GuestTime.day(time) + 1,
                      clock,
                      sample.state().type().displayName(),
                      format(sample.state().intensity()),
                      format(sample.temperature())),
              false);
    }
    return segments;
  }

  private static int force(
      CommandContext<CommandSourceStack> context, float intensity, int minutes) {
    ServerLevel level = overworld(context);
    if (level == null) {
      return 0;
    }
    String name = StringArgumentType.getString(context, "type");

    boolean aurora = name.equalsIgnoreCase(AURORA);
    WeatherType type =
        aurora
            ? WeatherType.CLEAR
            : Arrays.stream(WeatherType.values())
                .filter(value -> value.name().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    if (type == null) {
      context
          .getSource()
          .sendFailure(Component.translatable("guest_atmosphere.command.unknown_type", name));
      return 0;
    }
    BlockPos pos = BlockPos.containing(context.getSource().getPosition());
    int radius = AtmosphereConfig.weather().cellSize() / 2;
    long now = GuestTime.gameTime(level);
    AtmosphereWeather.force(
        new AtmosphereWeather.Forced(
            pos.getX(),
            pos.getZ(),
            radius,
            type,
            intensity,
            aurora,
            now,
            now + (long) minutes * TICKS_PER_MINUTE));
    context
        .getSource()
        .sendSuccess(
            () ->
                Component.translatable(
                    "guest_atmosphere.command.forced",
                    type.displayName(),
                    format(intensity),
                    radius,
                    minutes),
            true);
    return 1;
  }

  private static int unforce(CommandContext<CommandSourceStack> context) {
    int removed = AtmosphereWeather.clearForced();
    context
        .getSource()
        .sendSuccess(
            () -> Component.translatable("guest_atmosphere.command.unforced", removed), true);
    return removed;
  }

  private static int traces(CommandContext<CommandSourceStack> context, int radius) {
    ServerLevel level = overworld(context);
    if (level == null) {
      return 0;
    }
    ChunkPos origin = ChunkPos.containing(BlockPos.containing(context.getSource().getPosition()));
    int[] byKind = new int[ChunkTraces.Kind.values().length];
    int chunks = 0;
    for (int dx = -radius; dx <= radius; dx++) {
      for (int dz = -radius; dz <= radius; dz++) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(origin.x() + dx, origin.z() + dz);
        ChunkTraces traces = chunk == null ? null : GuestAtmosphere.CHUNK_TRACES.getExisting(chunk);
        if (traces == null) {
          continue;
        }
        chunks++;
        for (ChunkTraces.Trace trace : traces.view().values()) {
          byKind[trace.kind().ordinal()]++;
        }
      }
    }
    int total = Arrays.stream(byKind).sum();
    int counted = chunks;
    context
        .getSource()
        .sendSuccess(
            () -> Component.translatable("guest_atmosphere.command.traces", total, counted), false);
    for (ChunkTraces.Kind kind : ChunkTraces.Kind.values()) {
      int count = byKind[kind.ordinal()];
      if (count > 0) {
        context
            .getSource()
            .sendSuccess(
                () ->
                    Component.translatable(
                        "guest_atmosphere.command.traces.kind", kind.displayName(), count),
                false);
      }
    }
    return total;
  }

  private static int catchUp(CommandContext<CommandSourceStack> context, int radius) {
    ServerLevel level = overworld(context);
    if (level == null) {
      return 0;
    }
    int chunks =
        TraceSimulator.catchUpAround(
            level, BlockPos.containing(context.getSource().getPosition()), radius);
    context
        .getSource()
        .sendSuccess(
            () -> Component.translatable("guest_atmosphere.command.catchup", chunks), true);
    return chunks;
  }

  private static ServerLevel overworld(CommandContext<CommandSourceStack> context) {
    ServerLevel level = context.getSource().getLevel();
    if (level.dimension() != Level.OVERWORLD) {
      context
          .getSource()
          .sendFailure(Component.translatable("guest_atmosphere.command.overworld_only"));
      return null;
    }
    return level;
  }

  private static String format(float value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }

  private static String percent(int count, int total) {
    return String.format(Locale.ROOT, "%.0f", 100.0 * count / Math.max(1, total));
  }
}
