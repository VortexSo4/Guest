package com.vortexso.guest_atmosphere.weather;

import com.vortexso.guest_atmosphere.AtmosphereConfig;
import com.vortexso.guest_atmosphere.GuestAtmosphere;
import com.vortexso.guest_atmosphere.network.WeatherSyncPayload;
import com.vortexso.guest_atmosphere.weather.WeatherModel.Climate;
import com.vortexso.guest_atmosphere.weather.WeatherModel.ClimateClass;
import com.vortexso.guest_atmosphere.weather.WeatherModel.Cover;
import com.vortexso.guest_atmosphere.weather.WeatherModel.Sample;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.world.WeatherState;
import com.vortexso.guest_core.api.world.WeatherType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

public final class AtmosphereWeather {

  private static final int SNOW_LINE_OFFSET = 17;

  private static final TagKey<Biome> C_IS_DESERT = conventional("is_desert");
  private static final TagKey<Biome> C_IS_FOREST = conventional("is_forest");
  private static final TagKey<Biome> C_IS_DECIDUOUS_TREE = conventional("is_tree/deciduous");
  private static final TagKey<Biome> C_IS_MOUNTAIN = conventional("is_mountain");
  private static final TagKey<Biome> C_IS_PLAINS = conventional("is_plains");
  private static final TagKey<Biome> C_IS_SNOWY_PLAINS = conventional("is_snowy_plains");
  private static final TagKey<Biome> C_IS_WINDSWEPT = conventional("is_windswept");
  private static final TagKey<Biome> C_IS_COLD_OVERWORLD = conventional("is_cold/overworld");
  private static final TagKey<Biome> C_IS_SWAMP = conventional("is_swamp");
  private static final TagKey<Biome> C_IS_WET_OVERWORLD = conventional("is_wet/overworld");

  private static final int OPEN_ALTITUDE = 60;

  private static final int LOWLAND_ALTITUDE = 6;

  private static final Map<Biome, BiomeInfo> BIOMES = new ConcurrentHashMap<>();
  private static final List<Forced> FORCED = new CopyOnWriteArrayList<>();

  private AtmosphereWeather() {}

  private static TagKey<Biome> conventional(String path) {
    return TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("c", path));
  }

  public static WeatherState get(Level level, BlockPos pos, long time) {
    if (level.isClientSide()) {
      return WeatherSyncPayload.latest().state();
    }
    if (!(level instanceof ServerLevel serverLevel) || level.dimension() != Level.OVERWORLD) {
      return WeatherState.CLEAR;
    }
    return sample(serverLevel, pos, time).state();
  }

  public static Sample sample(ServerLevel level, BlockPos pos, long time) {
    return sample(level, pos, time, climate(level, pos));
  }

  public static Sample sample(ServerLevel level, BlockPos pos, long time, Climate climate) {
    Sample modelled =
        WeatherModel.sample(
            level.getSeed(), time, pos.getX(), pos.getZ(), climate, AtmosphereConfig.weather());
    for (Forced forced : FORCED) {
      if (forced.covers(pos, time)) {
        float wind = forced.type().isSevere() ? 0.8F : modelled.state().wind();

        float temperature =
            forced.type().isSnow()
                ? Math.min(modelled.temperature(), -0.3F)
                : modelled.temperature();
        return new Sample(
            new WeatherState(forced.type(), forced.intensity(), wind, forced.aurora()),
            temperature,
            modelled.windAngle());
      }
    }
    return modelled;
  }

  public static Cover cover(ServerLevel level, BlockPos pos, long time) {
    Climate climate = climate(level, pos);
    return WeatherModel.cover(
        time, t -> sample(level, pos, t, climate), AtmosphereConfig.weather());
  }

  public static Climate climate(Level level, BlockPos pos) {
    Holder<Biome> holder =
        level.getNoiseBiome(
            QuartPos.fromBlock(pos.getX()),
            QuartPos.fromBlock(pos.getY()),
            QuartPos.fromBlock(pos.getZ()));
    BiomeInfo info = BIOMES.computeIfAbsent(holder.value(), biome -> BiomeInfo.of(holder));
    int seaLevel = level.getSeaLevel();
    float temperature =
        info.temperature()
            - Math.max(0, pos.getY() - (seaLevel + SNOW_LINE_OFFSET)) * 0.05F / 40.0F;
    ClimateClass climateClass;
    if (!info.precipitation() && info.temperature() > 1.0F) {
      climateClass = ClimateClass.DRY;
    } else if (info.boreal() || temperature <= 0.35F) {
      climateClass = ClimateClass.BOREAL;
    } else if (info.wet()) {
      climateClass = ClimateClass.WET;
    } else {
      climateClass = ClimateClass.TEMPERATE;
    }
    float dampness = info.damp() ? 1.0F : pos.getY() <= seaLevel + LOWLAND_ALTITUDE ? 0.35F : 0.0F;
    return new Climate(
        climateClass,
        temperature,
        info.sandy(),
        info.forested(),
        info.open() || pos.getY() > seaLevel + OPEN_ALTITUDE,
        dampness);
  }

  public static boolean isRedSand(Level level, BlockPos pos) {
    return level.getBiome(pos).is(BiomeTags.IS_BADLANDS);
  }

  public static Biome.@Nullable Precipitation precipitationAt(Level level, BlockPos pos) {
    if (level.dimension() != Level.OVERWORLD || !driving(level)) {
      return null;
    }
    if (!level.canSeeSky(pos)
        || level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos).getY() > pos.getY()) {
      return Biome.Precipitation.NONE;
    }
    return precipitation(get(level, pos, GuestTime.gameTime(level)).type());
  }

  public static Biome.Precipitation precipitation(WeatherType type) {
    if (type.isRain()) {
      return Biome.Precipitation.RAIN;
    }
    return type.isSnow() ? Biome.Precipitation.SNOW : Biome.Precipitation.NONE;
  }

  public static boolean driving(Level level) {
    return level.isClientSide()
        ? WeatherSyncPayload.latest().driving()
        : AtmosphereConfig.DRIVE_VANILLA_WEATHER.get();
  }

  public static void force(Forced forced) {
    FORCED.removeIf(existing -> existing.x() == forced.x() && existing.z() == forced.z());
    FORCED.add(forced);
  }

  public static int clearForced() {
    int count = FORCED.size();
    FORCED.clear();
    return count;
  }

  public static void clearBiomeCache() {
    BIOMES.clear();
  }

  public record Forced(
      int x,
      int z,
      int radius,
      WeatherType type,
      float intensity,
      boolean aurora,
      long from,
      long until) {
    boolean covers(BlockPos pos, long time) {
      long dx = pos.getX() - x;
      long dz = pos.getZ() - z;
      return time >= from && time < until && dx * dx + dz * dz <= (long) radius * radius;
    }
  }

  private record BiomeInfo(
      float temperature,
      boolean precipitation,
      boolean sandy,
      boolean forested,
      boolean open,
      boolean boreal,
      boolean wet,
      boolean damp) {
    private static float downfall(Biome biome) {
      try {
        Field field = Biome.class.getDeclaredField("climateSettings");
        field.setAccessible(true);
        Object climate = field.get(biome);
        Method downfall = climate.getClass().getDeclaredMethod("downfall");
        downfall.setAccessible(true);
        return (float) downfall.invoke(climate);
      } catch (ReflectiveOperationException | RuntimeException exception) {
        GuestAtmosphere.LOGGER.warn("Cannot read biome downfall", exception);
        return 0.0F;
      }
    }

    static BiomeInfo of(Holder<Biome> holder) {
      Biome biome = holder.value();
      return new BiomeInfo(
          biome.getBaseTemperature(),
          biome.hasPrecipitation(),
          holder.is(C_IS_DESERT) || holder.is(BiomeTags.IS_BADLANDS),
          holder.is(BiomeTags.IS_FOREST)
              || holder.is(C_IS_FOREST)
              || holder.is(C_IS_DECIDUOUS_TREE),
          holder.is(BiomeTags.IS_MOUNTAIN)
              || holder.is(C_IS_MOUNTAIN)
              || holder.is(C_IS_PLAINS)
              || holder.is(C_IS_SNOWY_PLAINS)
              || holder.is(C_IS_WINDSWEPT),
          holder.is(BiomeTags.IS_TAIGA) || holder.is(C_IS_COLD_OVERWORLD),
          downfall(biome) >= 0.85F
              || holder.is(BiomeTags.IS_JUNGLE)
              || holder.is(C_IS_SWAMP)
              || holder.is(C_IS_WET_OVERWORLD),
          holder.is(BiomeTags.IS_RIVER) || holder.is(BiomeTags.IS_BEACH) || holder.is(C_IS_SWAMP));
    }
  }
}
