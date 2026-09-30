package com.vortexso.guest_atmosphere.client;

import com.vortexso.guest_atmosphere.AtmosphereConfig;
import com.vortexso.guest_atmosphere.GuestAtmosphere;
import com.vortexso.guest_atmosphere.block.AtmosphereBlocks;
import com.vortexso.guest_atmosphere.network.WeatherSyncPayload;
import com.vortexso.guest_core.api.GuestHash;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.color.block.BlockTintSources;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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

  private static volatile float snow;

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
    if (newSnow != snow || newDry != dry) {
      snow = newSnow;
      dry = newDry;
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

  static int apply(int color, float drySensitivity, BlockAndTintGetter level, BlockPos pos) {
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

  private record Tint(BlockTintSource inner, float drySensitivity) implements BlockTintSource {
    @Override
    public int color(BlockState state) {
      return inner.color(state);
    }

    @Override
    public int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
      return apply(inner.colorInWorld(state, level, pos), drySensitivity, level, pos);
    }

    @Override
    public int colorAsTerrainParticle(BlockState state, BlockAndTintGetter level, BlockPos pos) {
      return apply(inner.colorAsTerrainParticle(state, level, pos), drySensitivity, level, pos);
    }

    @Override
    public Set<Property<?>> relevantProperties() {
      return inner.relevantProperties();
    }
  }
}
