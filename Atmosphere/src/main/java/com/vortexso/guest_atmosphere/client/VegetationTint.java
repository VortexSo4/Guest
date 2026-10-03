package com.vortexso.guest_atmosphere.client;

import com.vortexso.guest_atmosphere.AtmosphereConfig;
import com.vortexso.guest_atmosphere.GuestAtmosphere;
import com.vortexso.guest_atmosphere.block.AtmosphereBlocks;
import com.vortexso.guest_atmosphere.network.WeatherSyncPayload;
import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.Season;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.color.block.BlockTintSources;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

@EventBusSubscriber(modid = GuestAtmosphere.MODID, value = Dist.CLIENT)
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

  private static final ThreadLocal<long[]> TREE =
      ThreadLocal.withInitial(() -> new long[] {Long.MIN_VALUE, 0L});

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

  @SubscribeEvent
  public static void onRegisterTints(RegisterColorHandlersEvent.BlockTintSources event) {
    wrap(event, GRASSES, 1.0F);
    event.register(
        List.of(new Tint(BlockTintSources.grass(), 1.0F)),
        AtmosphereBlocks.FROSTY_GRASS.get(),
        AtmosphereBlocks.FROSTY_FERN.get(),
        AtmosphereBlocks.CRYOSOL.get(),
        AtmosphereBlocks.SNOWY_PLANT.get(),
        AtmosphereBlocks.SANDY_PLANT.get(),
        AtmosphereBlocks.RED_SANDY_PLANT.get());
    event.register(List.of(BlockTintSources.foliage()), AtmosphereBlocks.IVY.get());

    wrap(event, FOLIAGE, 0.4F);
  }

  private static void wrap(
      RegisterColorHandlersEvent.BlockTintSources event, Block[] blocks, float drySensitivity) {
    for (Block block : blocks) {
      List<BlockTintSource> sources =
          event.getBlockColors().getTintSources(block.defaultBlockState());
      if (!sources.isEmpty()) {
        event.register(
            sources.stream()
                .<BlockTintSource>map(source -> new Tint(source, drySensitivity))
                .toList(),
            block);
      }
    }
  }

  @SubscribeEvent
  public static void onClientTick(ClientTickEvent.Post event) {
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
    if (newSnow != snow
        || newDry != dry
        || newSeason != season
        || newSparse != sparse
        || newAutumn != autumn) {
      snow = newSnow;
      dry = newDry;
      season = newSeason;
      sparse = newSparse;
      autumn = newAutumn;
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
    long tree = BlockPos.asLong(pos.getX() >> 3, 0, pos.getZ() >> 3);
    if (state.is(BlockTags.LOGS)) {
      if (level instanceof RenderSectionRegion) {
        int minY = pos.getY() - SectionPos.SECTION_SIZE;

        while (cursor.getY() > minY && level.getBlockState(cursor.below()).is(BlockTags.LOGS)) {
          cursor.move(Direction.DOWN);
        }
      } else {
        for (int i = 0; i < 32 && level.getBlockState(cursor.below()).is(BlockTags.LOGS); i++) {
          cursor.move(Direction.DOWN);
        }
      }

      tree = cursor.asLong();
    }
    cache[0] = pos.asLong();
    cache[1] = tree;
    return tree;
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
