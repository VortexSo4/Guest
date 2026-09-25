package com.vortexso.guest_atmosphere.weather;

import com.vortexso.guest_atmosphere.AtmosphereConfig;
import com.vortexso.guest_atmosphere.network.WeatherSyncPayload;
import com.vortexso.guest_atmosphere.weather.WeatherModel.Climate;
import com.vortexso.guest_atmosphere.weather.WeatherModel.ClimateClass;
import com.vortexso.guest_atmosphere.weather.WeatherModel.Sample;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.world.WeatherState;
import com.vortexso.guest_core.api.world.WeatherType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.Tags;
import org.jspecify.annotations.Nullable;

/**
 * Connects the pure {@link WeatherModel} to a level: reads the biome, applies test overrides and
 * serves Core's {@code GuestWeather} provider. Only the overworld has regional weather.
 */
public final class AtmosphereWeather {
  /** Vanilla: temperature drops above sea level + 17 by 0.05 per 40 blocks (noise omitted). */
  private static final int SNOW_LINE_OFFSET = 17;

  /** Blocks above sea level where terrain counts as open country for snowstorms. */
  private static final int OPEN_ALTITUDE = 60;

  private static final Map<Biome, BiomeInfo> BIOMES = new ConcurrentHashMap<>();
  private static final List<Forced> FORCED = new CopyOnWriteArrayList<>();

  private AtmosphereWeather() {}

  /** Core provider. Client levels report the region around the local player. */
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
    Climate climate = climate(level, pos);
    Sample modelled =
        WeatherModel.sample(
            level.getSeed(), time, pos.getX(), pos.getZ(), climate, AtmosphereConfig.weather());
    for (Forced forced : FORCED) {
      if (forced.covers(pos, time)) {
        float wind = forced.type().isSevere() ? 0.8F : modelled.state().wind();
        // A forced snow type must actually snow, so it also forces the cold.
        float temperature =
            forced.type().isSnow()
                ? Math.min(modelled.temperature(), -0.3F)
                : modelled.temperature();
        return new Sample(
            new WeatherState(forced.type(), forced.intensity(), wind, false),
            temperature,
            modelled.windAngle());
      }
    }
    return modelled;
  }

  public static Climate climate(Level level, BlockPos pos) {
    Holder<Biome> holder = level.getBiome(pos);
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
    return new Climate(
        climateClass,
        temperature,
        info.sandy(),
        info.forested(),
        info.open() || pos.getY() > seaLevel + OPEN_ALTITUDE);
  }

  public static boolean isRedSand(Level level, BlockPos pos) {
    return level.getBiome(pos).is(BiomeTags.IS_BADLANDS);
  }

  /**
   * Replacement for {@code Level.precipitationAt} while the model drives weather: rain/snow is
   * regional instead of global. Returns null to keep vanilla behaviour.
   */
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

  /** Server uses its config; a client follows what its server announced. */
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

  /** Test override for a circular area; kept in memory only, it is not world state. */
  public record Forced(int x, int z, int radius, WeatherType type, float intensity, long until) {
    boolean covers(BlockPos pos, long time) {
      long dx = pos.getX() - x;
      long dz = pos.getZ() - z;
      return time < until && dx * dx + dz * dz <= (long) radius * radius;
    }
  }

  private record BiomeInfo(
      float temperature,
      boolean precipitation,
      boolean sandy,
      boolean forested,
      boolean open,
      boolean boreal,
      boolean wet) {
    static BiomeInfo of(Holder<Biome> holder) {
      Biome.ClimateSettings climate = holder.value().getModifiedClimateSettings();
      return new BiomeInfo(
          climate.temperature(),
          climate.hasPrecipitation(),
          holder.is(Tags.Biomes.IS_DESERT) || holder.is(BiomeTags.IS_BADLANDS),
          holder.is(BiomeTags.IS_FOREST)
              || holder.is(Tags.Biomes.IS_FOREST)
              || holder.is(Tags.Biomes.IS_DECIDUOUS_TREE),
          holder.is(BiomeTags.IS_MOUNTAIN)
              || holder.is(Tags.Biomes.IS_MOUNTAIN)
              || holder.is(Tags.Biomes.IS_PLAINS)
              || holder.is(Tags.Biomes.IS_SNOWY_PLAINS)
              || holder.is(Tags.Biomes.IS_WINDSWEPT),
          holder.is(BiomeTags.IS_TAIGA) || holder.is(Tags.Biomes.IS_COLD_OVERWORLD),
          climate.downfall() >= 0.85F
              || holder.is(BiomeTags.IS_JUNGLE)
              || holder.is(Tags.Biomes.IS_SWAMP)
              || holder.is(Tags.Biomes.IS_WET_OVERWORLD));
    }
  }
}
