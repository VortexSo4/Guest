package com.vortexso.guest_settlements.society;

import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_settlements.GuestSettlements;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.GravityProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jspecify.annotations.Nullable;

@EventBusSubscriber(modid = GuestSettlements.MODID)
public final class Traces {
  public static final Identifier CARAVAN_WRECK = GuestSettlements.id("caravan_wreck");
  public static final Identifier ILLAGER_TENT = GuestSettlements.id("illager_tent");
  public static final Identifier ILLAGER_PEN = GuestSettlements.id("illager_pen");

  private static final long EVENT_FADE = 0xFADEL;

  private static final int REVERT_FLAGS =
      Block.UPDATE_ALL | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

  private static final int FADE_INTERVAL = 100;

  private static final int TERRAIN_REACH = 16;

  private Traces() {}

  @SubscribeEvent
  public static void onLevelTick(LevelTickEvent.Post event) {
    if (event.getLevel() instanceof ServerLevel level && level.getGameTime() % FADE_INTERVAL == 0) {
      fade(level);
    }
  }

  public static @Nullable BlockPos findSite(
      ServerLevel level,
      Identifier template,
      BlockPos center,
      int radius,
      long seed,
      boolean followTerrain) {
    Optional<StructureTemplate> found = level.getStructureManager().get(template);
    if (found.isEmpty()) {
      GuestSettlements.LOGGER.warn("Missing structure template {}", template);
      return null;
    }
    for (int attempt = 0; attempt < 12; attempt++) {
      long hash = GuestHash.hash(seed, attempt);
      double angle = GuestHash.unit(hash) * Math.PI * 2.0;
      double distance = radius * (attempt == 0 ? 0.0 : 0.3 + 0.7 * GuestHash.unit(hash >>> 7));
      int x = center.getX() + (int) Math.round(Math.cos(angle) * distance);
      int z = center.getZ() + (int) Math.round(Math.sin(angle) * distance);
      BlockPos origin =
          new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
      BoundingBox box = found.get().getBoundingBox(settings(Rotation.NONE, false), origin);
      if (followTerrain ? even(level, box) : fits(level, box)) {
        return origin;
      }
    }
    return null;
  }

  private static boolean fits(ServerLevel level, BoundingBox box) {
    if (!level.isLoaded(new BlockPos(box.minX(), box.minY(), box.minZ()))
        || !level.isLoaded(new BlockPos(box.maxX(), box.minY(), box.maxZ()))) {
      return false;
    }
    int firm = 0;
    int total = 0;
    for (BlockPos pos :
        BlockPos.betweenClosed(
            box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
      BlockState state = level.getBlockState(pos);
      if (!(state.isAir() || state.canBeReplaced()) || !state.getFluidState().isEmpty()) {
        return false;
      }
      if (pos.getY() == box.minY()) {
        total++;
        BlockPos below = pos.below();
        if (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
          firm++;
        }
      }
    }
    return firm * 4 >= total * 3;
  }

  private static boolean even(ServerLevel level, BoundingBox box) {
    int min = Integer.MAX_VALUE;
    int max = Integer.MIN_VALUE;
    for (int x = box.minX(); x <= box.maxX(); x++) {
      for (int z = box.minZ(); z <= box.maxZ(); z++) {
        BlockPos top =
            new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
        if (!level.isLoaded(top)) {
          return false;
        }
        BlockState here = level.getBlockState(top);
        BlockPos below = top.below();
        if (!(here.isAir() || here.canBeReplaced())
            || !here.getFluidState().isEmpty()
            || !level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
          return false;
        }
        min = Math.min(min, top.getY());
        max = Math.max(max, top.getY());
      }
    }
    return max - min <= 1;
  }

  public static boolean placeTemplate(
      ServerLevel level,
      long id,
      Identifier template,
      BlockPos origin,
      Rotation rotation,
      boolean followTerrain,
      long time,
      long lifetime) {
    Optional<StructureTemplate> found = level.getStructureManager().get(template);
    if (found.isEmpty()) {
      GuestSettlements.LOGGER.warn("Missing structure template {}", template);
      return false;
    }
    StructurePlaceSettings settings = settings(rotation, followTerrain);
    BoundingBox box = found.get().getBoundingBox(settings, origin);
    if (followTerrain) {
      box = box.inflatedBy(0, TERRAIN_REACH, 0);
    }
    Map<BlockPos, BlockState> before = new HashMap<>();
    for (BlockPos pos :
        BlockPos.betweenClosed(
            box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
      before.put(pos.immutable(), level.getBlockState(pos));
    }
    found.get().placeInWorld(level, origin, origin, settings, level.getRandom(), Block.UPDATE_ALL);
    List<SocietyData.TracedBlock> changed = new ArrayList<>();
    before.forEach(
        (pos, original) -> {
          BlockState placed = level.getBlockState(pos);
          if (!placed.equals(original)) {
            changed.add(new SocietyData.TracedBlock(pos, placed, original));
          }
        });
    record(level, id, template.getPath(), time, lifetime, changed);
    return true;
  }

  public static void placeBlocks(
      ServerLevel level,
      long id,
      String kind,
      Map<BlockPos, BlockState> blocks,
      long time,
      long lifetime) {
    List<SocietyData.TracedBlock> changed = new ArrayList<>();
    blocks.forEach(
        (pos, state) -> {
          BlockState original = level.getBlockState(pos);
          if (!original.equals(state) && level.setBlock(pos, state, Block.UPDATE_ALL)) {
            changed.add(new SocietyData.TracedBlock(pos.immutable(), state, original));
          }
        });
    record(level, id, kind, time, lifetime, changed);
  }

  private static void record(
      ServerLevel level,
      long id,
      String kind,
      long time,
      long lifetime,
      List<SocietyData.TracedBlock> blocks) {
    SocietyData.get(level)
        .putTrace(new SocietyData.Trace(id, kind, time, lifetime, List.copyOf(blocks)));

    fade(level);
  }

  public static boolean exists(ServerLevel level, long id) {
    return SocietyData.get(level).traces.containsKey(id);
  }

  public static void remove(ServerLevel level, long id) {
    SocietyData data = SocietyData.get(level);
    SocietyData.Trace trace = data.traces.get(id);
    if (trace != null) {
      data.putTrace(
          new SocietyData.Trace(id, trace.kind(), GuestTime.gameTime(level), 0L, trace.blocks()));
      fade(level);
    }
  }

  public static void fade(ServerLevel level) {
    SocietyData data = SocietyData.get(level);
    long now = GuestTime.gameTime(level);
    for (SocietyData.Trace trace : List.copyOf(data.traces.values())) {
      if (trace.lifetime() < 0) {
        continue;
      }
      List<SocietyData.TracedBlock> left = new ArrayList<>();
      for (SocietyData.TracedBlock block : trace.blocks()) {
        if (now < expiry(level, trace, block) || !level.isLoaded(block.pos())) {
          left.add(block);
        } else if (level.getBlockState(block.pos()).equals(block.placed())) {
          level.setBlock(block.pos(), block.original(), REVERT_FLAGS);
        }
      }
      if (left.isEmpty() && now >= trace.time() + trace.lifetime()) {
        data.removeTrace(trace.id());
      } else if (left.size() != trace.blocks().size()) {
        data.putTrace(
            new SocietyData.Trace(
                trace.id(), trace.kind(), trace.time(), trace.lifetime(), List.copyOf(left)));
      }
    }
  }

  public static long expiry(
      ServerLevel level, SocietyData.Trace trace, SocietyData.TracedBlock block) {
    double share =
        0.35
            + 0.65
                * GuestHash.unit(
                    GuestHash.hash(level.getSeed(), trace.id(), block.pos().asLong(), EVENT_FADE));
    return trace.time() + (long) (trace.lifetime() * share);
  }

  private static StructurePlaceSettings settings(Rotation rotation, boolean followTerrain) {
    StructurePlaceSettings settings =
        new StructurePlaceSettings()
            .setRotation(rotation)
            .setIgnoreEntities(true)
            .addProcessor(JigsawReplacementProcessor.INSTANCE);
    if (followTerrain) {
      settings.addProcessor(new GravityProcessor(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0));
    }
    return settings;
  }
}
