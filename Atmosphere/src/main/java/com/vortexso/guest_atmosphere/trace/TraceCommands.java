package com.vortexso.guest_atmosphere.trace;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.vortexso.guest_atmosphere.AtmosphereConfig;
import com.vortexso.guest_atmosphere.GuestAtmosphere;
import com.vortexso.guest_atmosphere.block.Coating;
import com.vortexso.guest_core.api.GuestTime;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = GuestAtmosphere.MODID)
public final class TraceCommands {
  private TraceCommands() {}

  @SubscribeEvent
  public static void register(RegisterCommandsEvent event) {
    event
        .getDispatcher()
        .register(
            Commands.literal("guest")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("atmosphere")
                        .then(Commands.literal("column").executes(TraceCommands::column))
                        .then(
                            Commands.literal("simulate")
                                .then(
                                    Commands.argument(
                                            "ticks", IntegerArgumentType.integer(1, 24_000))
                                        .executes(context -> simulate(context, 1))
                                        .then(
                                            Commands.argument(
                                                    "radius", IntegerArgumentType.integer(0, 4))
                                                .executes(
                                                    context ->
                                                        simulate(
                                                            context,
                                                            IntegerArgumentType.getInteger(
                                                                context, "radius"))))))
                        .then(
                            Commands.literal("blocks")
                                .executes(context -> blocks(context, 1))
                                .then(
                                    Commands.argument("radius", IntegerArgumentType.integer(0, 8))
                                        .executes(
                                            context ->
                                                blocks(
                                                    context,
                                                    IntegerArgumentType.getInteger(
                                                        context, "radius")))))));
  }

  private static int column(CommandContext<CommandSourceStack> context) {
    ServerLevel level = overworld(context);
    if (level == null) {
      return 0;
    }
    BlockPos pos = BlockPos.containing(context.getSource().getPosition());
    BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos);
    long now = GuestTime.gameTime(level);
    long span = AtmosphereConfig.CATCH_UP_MAX_DAYS.get() * GuestTime.TICKS_PER_DAY;
    History.Params params = History.Params.current();
    History h = Column.replay(level, top, now - span, now, 0.0, now - span, params);
    CommandSourceStack source = context.getSource();
    source.sendSuccess(
        () ->
            Component.translatable(
                "guest_atmosphere.command.column",
                top.getX(),
                top.getZ(),
                level.getBlockState(top).getBlock().getName(),
                level.getBlockState(top.below()).getBlock().getName()),
        false);
    source.sendSuccess(
        () ->
            Component.translatable(
                "guest_atmosphere.command.column.snow",
                format(h.snowDepth),
                AtmosphereConfig.CATCH_UP_MAX_DAYS.get(),
                format(params.snowTemperature()),
                format(params.meltTemperature())),
        false);
    source.sendSuccess(
        () ->
            Component.translatable(
                "guest_atmosphere.command.column.rain",
                ago(h.lastRealRain, now),
                format(AtmosphereConfig.MUD_DRYING_DAYS.get()),
                ago(h.lastHeavyRain, now),
                ago(h.lastSandstorm, now),
                ago(h.lastWind, now),
                ago(h.lastHeat, now)),
        false);
    source.sendSuccess(
        () ->
            Component.translatable(
                "guest_atmosphere.command.column.cold",
                format(h.coldFor() / (double) GuestTime.TICKS_PER_DAY),
                format(h.warmFor() / (double) GuestTime.TICKS_PER_DAY),
                h.freezeThawCycles,
                format(h.dampDays),
                format(h.litterDays)),
        false);
    return 1;
  }

  private static int simulate(CommandContext<CommandSourceStack> context, int radius) {
    ServerLevel level = overworld(context);
    if (level == null) {
      return 0;
    }
    int ticks = IntegerArgumentType.getInteger(context, "ticks");
    int chunks =
        TraceSimulator.simulate(
            level, BlockPos.containing(context.getSource().getPosition()), radius, ticks);
    context
        .getSource()
        .sendSuccess(
            () -> Component.translatable("guest_atmosphere.command.simulate", ticks, chunks), true);
    return chunks;
  }

  private static int blocks(CommandContext<CommandSourceStack> context, int radius) {
    ServerLevel level = overworld(context);
    if (level == null) {
      return 0;
    }
    ChunkPos origin = ChunkPos.containing(BlockPos.containing(context.getSource().getPosition()));
    Object2IntOpenHashMap<Block> counts = new Object2IntOpenHashMap<>();
    for (int dx = -radius; dx <= radius; dx++) {
      for (int dz = -radius; dz <= radius; dz++) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(origin.x() + dx, origin.z() + dz);
        if (chunk == null) {
          continue;
        }
        for (LevelChunkSection section : chunk.getSections()) {
          if (section.hasOnlyAir() || !section.maybeHas(TraceCommands::counted)) {
            continue;
          }
          for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
              for (int z = 0; z < 16; z++) {
                BlockState state = section.getBlockState(x, y, z);
                if (counted(state)) {
                  counts.addTo(state.getBlock(), 1);
                }
              }
            }
          }
        }
      }
    }
    Map<String, Integer> sorted = new TreeMap<>();
    counts.forEach(
        (block, count) -> sorted.put(BuiltInRegistries.BLOCK.getKey(block).toString(), count));
    context
        .getSource()
        .sendSuccess(
            () -> Component.translatable("guest_atmosphere.command.blocks", sorted.size()), false);
    sorted.forEach(
        (name, count) ->
            context
                .getSource()
                .sendSuccess(() -> Component.literal("  " + name + ": " + count), false));
    return counts.values().intStream().sum();
  }

  private static boolean counted(BlockState state) {
    Block block = state.getBlock();
    return BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals(GuestAtmosphere.MODID)
        || Coating.coatOf(state) != null
        || state.is(Blocks.SNOW)
        || state.is(Blocks.MUD)
        || state.is(Blocks.LEAF_LITTER)
        || GrowthTraces.unmossed(block) != null
        || GrowthTraces.uncracked(block) != null;
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

  private static Component ago(long time, long now) {
    return time == History.NEVER
        ? Component.translatable("guest_atmosphere.command.column.never")
        : Component.translatable(
            "guest_atmosphere.command.column.days_ago",
            format((now - time) / (double) GuestTime.TICKS_PER_DAY));
  }

  private static String format(double value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }
}
