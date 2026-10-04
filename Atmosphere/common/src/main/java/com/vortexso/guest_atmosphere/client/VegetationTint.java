package com.vortexso.guest_atmosphere.client;

import com.vortexso.guest_atmosphere.AtmosphereConfig;
import com.vortexso.guest_atmosphere.block.AtmosphereBlocks;
import com.vortexso.guest_atmosphere.network.WeatherSyncPayload;
import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.Season;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.color.block.BlockTintSources;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

public final class VegetationTint {
  private static final int STEPS = 4;

  private static final int FROST = 0xFFEEF2F6;

  private static final int STRAW = 0xFFC9B35C;
  private static final long SALT = 0x2545F4914F6CDD1DL;

  private static final Block[] GRASSES = {
    Blocks.GRASS_BLOCK,
    Blocks.SHORT_GRASS,
    Blocks.TALL_GRASS,
    Blocks.FERN,
    Blocks.LARGE_FERN,
    Blocks.BUSH,
    Blocks.PINK_PETALS,
    Blocks.WILDFLOWERS,
    Blocks.SUGAR_CANE
  };

  private static final Block[] FOLIAGE = {
    Blocks.OAK_LEAVES,
    Blocks.SPRUCE_LEAVES,
    Blocks.BIRCH_LEAVES,
    Blocks.JUNGLE_LEAVES,
    Blocks.ACACIA_LEAVES,
    Blocks.DARK_OAK_LEAVES,
    Blocks.MANGROVE_LEAVES,
    Blocks.VINE,
    Blocks.LEAF_LITTER
  };

  private static final int AUTUMN_STEPS = 16;

  private static final long SALT_TREE = 0xA54FF53A5F1D36F1L;

  private static final int BUD = 0xFF9ACD4E;

  private static final int WITHERED = 0xFF8C6A3C;

  private static final int[] AUTUMN = {0xFFE3B53A, 0xFFDB8A2C, 0xFFC4562A, 0xFFA8321F};

  private static final int TRUNK_DEPTH = SectionPos.SECTION_SIZE;

  private static final int TRUNK_SEARCH = 256;

  private static final int TRUNK_CACHE = 16_384;

  private static final ThreadLocal<Long2LongOpenHashMap> TRUNKS =
      ThreadLocal.withInitial(Long2LongOpenHashMap::new);

  private static final ThreadLocal<int[]> TRUNK_EPOCH = ThreadLocal.withInitial(() -> new int[1]);

  private static volatile int epoch;

  private static final ThreadLocal<long[]> TREE =
      ThreadLocal.withInitial(() -> new long[] {Long.MIN_VALUE, 0L});

  private static final long REBUILD_INTERVAL = 200L;

  private static long lastRebuild = Long.MIN_VALUE / 2;

  private static volatile float snow;

  private static volatile boolean sparse;

  private static volatile int season = -1;

  private static volatile float autumn = -1.0F;

  private static volatile float dry;

  private static float smoothSnow;
  private static float smoothDry;

  private VegetationTint() {}

  public static float snow() {
    return snow;
  }

  public static float dryness() {
    return dry;
  }

  public static boolean sparse() {
    return sparse;
  }

  public static void registerTints(BlockColors colors) {
    wrap(colors, GRASSES, 1.0F);
    colors.register(
        List.of(new Tint(BlockTintSources.grass(), 1.0F)),
        AtmosphereBlocks.FROSTY_GRASS.get(),
        AtmosphereBlocks.FROSTY_FERN.get(),
        AtmosphereBlocks.CRYOSOL.get(),
        AtmosphereBlocks.SNOWY_PLANT.get(),
        AtmosphereBlocks.SANDY_PLANT.get(),
        AtmosphereBlocks.RED_SANDY_PLANT.get());
    colors.register(List.of(BlockTintSources.foliage()), AtmosphereBlocks.IVY.get());

    wrap(colors, FOLIAGE, 0.4F);
  }

  private static void wrap(BlockColors colors, Block[] blocks, float drySensitivity) {
    for (Block block : blocks) {
      List<BlockTintSource> sources = colors.getTintSources(block.defaultBlockState());
      if (!sources.isEmpty()) {
        colors.register(
            sources.stream()
                .<BlockTintSource>map(source -> new Tint(source, drySensitivity))
                .toList(),
            block);
      }
    }
  }

  public static void tick() {
    Minecraft minecraft = Minecraft.getInstance();
    ClientLevel level = minecraft.level;
    if (level == null || minecraft.player == null) {
      return;
    }
    WeatherSyncPayload weather = WeatherSyncPayload.latest();
    boolean enabled =
        AtmosphereConfig.VEGETATION_TINT.get()
            && weather.driving()
            && level.dimension() == Level.OVERWORLD;
    smoothSnow = approach(smoothSnow, enabled ? weather.cover().snow() : 0.0F);
    smoothDry = approach(smoothDry, enabled ? weather.cover().dryness() : 0.0F);
    float newSnow = step(snow, smoothSnow);
    float newDry = step(dry, smoothDry);
    boolean seasonal =
        AtmosphereConfig.SEASONAL_LEAVES.get() && level.dimension() == Level.OVERWORLD;
    long time = level.getOverworldClockTime();
    Season now = GuestTime.season(time);
    int newSeason = seasonal ? now.ordinal() : -1;
    boolean newSparse = seasonal && (now == Season.WINTER || now == Season.SPRING);
    float newAutumn =
        seasonal && now == Season.AUTUMN
            ? (float) Math.floor(GuestTime.seasonProgress(time) * AUTUMN_STEPS) / AUTUMN_STEPS
            : -1.0F;
    boolean seasonChanged = newSeason != season || newSparse != sparse || newAutumn != autumn;
    boolean coverChanged =
        (newSnow != snow || newDry != dry) && level.getGameTime() - lastRebuild >= REBUILD_INTERVAL;
    if (seasonChanged || coverChanged) {
      lastRebuild = level.getGameTime();
      snow = newSnow;
      dry = newDry;
      season = newSeason;
      sparse = newSparse;
      autumn = newAutumn;
      epoch++;
      ChunkPos center = minecraft.player.chunkPosition();
      int radius = minecraft.options.getEffectiveRenderDistance() + 1;
      minecraft.levelRenderer.setSectionRangeDirty(
          center.x() - radius,
          level.getMinSectionY(),
          center.z() - radius,
          center.x() + radius,
          level.getMaxSectionY(),
          center.z() + radius);
    }
  }

  private static float step(float current, float smooth) {
    if (Math.abs(smooth - current) < 0.6F / STEPS) {
      return current;
    }
    return Math.round(smooth * STEPS) / (float) STEPS;
  }

  private static float approach(float value, float target) {
    return value + Mth.clamp(target - value, -0.01F, 0.01F);
  }

  static int apply(
      int color, float drySensitivity, BlockState state, BlockAndTintGetter level, BlockPos pos) {
    if (color != -1 && state.is(BlockTags.LEAVES) && !state.is(Blocks.SPRUCE_LEAVES)) {
      color = seasonal(color, state, level, pos);
    }
    float s = snow;
    float d = dry * drySensitivity;
    if (color == -1 || (s <= 0.0F && d <= 0.0F)) {
      return color;
    }
    if (d > 0.0F) {
      color = ARGB.srgbLerp(0.6F * d, color, STRAW);
    }
    if (s > 0.0F) {

      int sky =
          Math.max(
              level.getBrightness(LightLayer.SKY, pos),
              level.getBrightness(LightLayer.SKY, pos.above()));
      float exposure = sky / 15.0F;
      float spread = 0.8F + 0.4F * (float) GuestHash.unit(GuestHash.hash(SALT, pos.asLong()));
      color = ARGB.srgbLerp(Math.min(1.0F, s * exposure * exposure * spread), color, FROST);
    }
    return color;
  }

  private static int seasonal(int color, BlockState state, BlockAndTintGetter level, BlockPos pos) {
    int current = season;
    if (current == Season.SPRING.ordinal()) {
      return ARGB.srgbLerp(0.3F, color, BUD);
    }
    if (current == Season.WINTER.ordinal()) {
      return ARGB.srgbLerp(0.45F, color, WITHERED);
    }
    float progress = autumn;
    if (progress < 0.0F) {
      return color;
    }
    long hash = GuestHash.hash(SALT_TREE, tree(level, pos));
    float turn = smoothstep((progress - 0.05F - 0.5F * unit(hash, 0)) / 0.25F);
    if (turn <= 0.0F) {
      return color;
    }
    boolean tropical =
        state.is(Blocks.JUNGLE_LEAVES)
            || state.is(Blocks.ACACIA_LEAVES)
            || state.is(Blocks.MANGROVE_LEAVES);
    int hue;
    if (unit(hash, 1) < (tropical ? 0.75F : 0.12F)) {
      hue = color;
    } else if (state.is(Blocks.BIRCH_LEAVES)) {
      hue = unit(hash, 2) < 0.8F ? AUTUMN[0] : AUTUMN[1];
    } else {
      hue = AUTUMN[(int) (unit(hash, 2) * AUTUMN.length)];
    }
    float wither = 0.6F * smoothstep((progress - 0.75F) / 0.25F);
    return ARGB.srgbLerp(turn, color, ARGB.srgbLerp(wither, hue, WITHERED));
  }

  private static long tree(BlockAndTintGetter level, BlockPos pos) {
    long[] cache = TREE.get();
    if (cache[0] == pos.asLong()) {
      return cache[1];
    }
    BlockPos.MutableBlockPos cursor = pos.mutable();
    BlockState state = level.getBlockState(cursor);
    for (int step = 0;
        step < LeavesBlock.DECAY_DISTANCE && state.hasProperty(LeavesBlock.DISTANCE);
        step++) {
      int distance = state.getValue(LeavesBlock.DISTANCE);
      BlockState next = null;
      for (Direction direction : Direction.values()) {
        cursor.move(direction);
        BlockState side = level.getBlockState(cursor);
        if (side.is(BlockTags.LOGS)
            || (side.hasProperty(LeavesBlock.DISTANCE)
                && side.getValue(LeavesBlock.DISTANCE) < distance)) {
          next = side;
          break;
        }
        cursor.move(direction.getOpposite());
      }
      if (next == null) {
        break;
      }
      state = next;
    }
    long tree =
        state.is(BlockTags.LOGS)
            ? trunk(level, cursor.immutable(), pos.getY() - TRUNK_DEPTH)
            : BlockPos.asLong(pos.getX() >> 3, 0, pos.getZ() >> 3);
    cache[0] = pos.asLong();
    cache[1] = tree;
    return tree;
  }

  private static long trunk(BlockAndTintGetter level, BlockPos log, int minY) {
    Long2LongOpenHashMap known = TRUNKS.get();
    int[] seenEpoch = TRUNK_EPOCH.get();
    if (known.size() > TRUNK_CACHE || seenEpoch[0] != epoch) {
      known.clear();
      seenEpoch[0] = epoch;
    }
    long cached = known.getOrDefault(log.asLong(), Long.MIN_VALUE);
    if (cached != Long.MIN_VALUE) {
      return cached;
    }
    PriorityQueue<BlockPos> open =
        new PriorityQueue<>(
            Comparator.<BlockPos>comparingInt(at -> at.getY())
                .thenComparingInt(at -> at.getX())
                .thenComparingInt(at -> at.getZ()));
    LongOpenHashSet seen = new LongOpenHashSet();
    LongArrayList visited = new LongArrayList();
    open.add(log);
    seen.add(log.asLong());
    BlockPos lowest = log;
    long result = Long.MIN_VALUE;
    while (!open.isEmpty() && visited.size() < TRUNK_SEARCH) {
      BlockPos current = open.poll();
      visited.add(current.asLong());
      long memo = known.getOrDefault(current.asLong(), Long.MIN_VALUE);
      if (memo != Long.MIN_VALUE) {
        result = memo;
        break;
      }
      if (current.getY() < lowest.getY()
          || (current.getY() == lowest.getY()
              && (current.getX() < lowest.getX()
                  || (current.getX() == lowest.getX() && current.getZ() < lowest.getZ())))) {
        lowest = current;
      }
      for (int dy = -1; dy <= 0; dy++) {
        if (current.getY() + dy < minY) {
          continue;
        }
        for (int dx = -1; dx <= 1; dx++) {
          for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dy == 0 && dz == 0) {
              continue;
            }
            BlockPos next = current.offset(dx, dy, dz);
            if (seen.add(next.asLong()) && level.getBlockState(next).is(BlockTags.LOGS)) {
              open.add(next);
            }
          }
        }
      }
    }
    if (result == Long.MIN_VALUE) {
      result = BlockPos.asLong(lowest.getX(), 0, lowest.getZ());
    }
    for (int i = 0; i < visited.size(); i++) {
      known.put(visited.getLong(i), result);
    }
    return result;
  }

  private static float unit(long hash, int index) {
    return (float) GuestHash.unit(GuestHash.hash(hash, index));
  }

  private static float smoothstep(float t) {
    float c = Mth.clamp(t, 0.0F, 1.0F);
    return c * c * (3.0F - 2.0F * c);
  }

  private record Tint(BlockTintSource inner, float drySensitivity) implements BlockTintSource {
    @Override
    public int color(BlockState state) {
      return inner.color(state);
    }

    @Override
    public int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
      return apply(inner.colorInWorld(state, level, pos), drySensitivity, state, level, pos);
    }

    @Override
    public int colorAsTerrainParticle(BlockState state, BlockAndTintGetter level, BlockPos pos) {
      return apply(
          inner.colorAsTerrainParticle(state, level, pos), drySensitivity, state, level, pos);
    }

    @Override
    public Set<Property<?>> relevantProperties() {
      return inner.relevantProperties();
    }
  }
}
