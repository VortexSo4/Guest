package com.vortexso.guest_atmosphere.weather;

import static org.junit.jupiter.api.Assertions.*;

import com.vortexso.guest_atmosphere.weather.WeatherModel.Climate;
import com.vortexso.guest_atmosphere.weather.WeatherModel.ClimateClass;
import com.vortexso.guest_atmosphere.weather.WeatherModel.Sample;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.Season;
import com.vortexso.guest_core.api.world.WeatherType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class WeatherModelTest {
  private static final long SEED = 987654321L;
  private static final WeatherParameters P = WeatherParameters.DEFAULT;

  private static final Climate PLAINS =
      new Climate(ClimateClass.TEMPERATE, 0.8F, false, false, true);
  private static final Climate FOREST =
      new Climate(ClimateClass.TEMPERATE, 0.7F, false, true, false);
  private static final Climate TAIGA = new Climate(ClimateClass.BOREAL, 0.25F, false, false, false);
  private static final Climate DESERT = new Climate(ClimateClass.DRY, 2.0F, true, false, false);
  private static final Climate SAVANNA = new Climate(ClimateClass.DRY, 2.0F, false, false, false);

  /** Visits many regions and every quarter-segment of the given season over several years. */
  private static void forSeason(Climate climate, Season season, Consumer<Sample> consumer) {
    for (int year = 0; year < 3; year++) {
      long seasonStart =
          (year * (long) GuestTime.DAYS_PER_YEAR
                  + season.ordinal() * (long) GuestTime.DAYS_PER_SEASON)
              * GuestTime.TICKS_PER_DAY;
      for (long t = 0; t < GuestTime.DAYS_PER_SEASON * GuestTime.TICKS_PER_DAY; t += 1_500) {
        for (int region = 0; region < 12; region++) {
          int x = region * 1_700 - 9_000;
          int z = region * 2_300 - 13_000;
          consumer.accept(WeatherModel.sample(SEED, seasonStart + t, x, z, climate, P));
        }
      }
    }
  }

  private static Map<WeatherType, Integer> histogram(Climate climate, Season season) {
    Map<WeatherType, Integer> counts = new EnumMap<>(WeatherType.class);
    forSeason(climate, season, s -> counts.merge(s.state().type(), 1, Integer::sum));
    return counts;
  }

  private static double share(
      Map<WeatherType, Integer> counts, java.util.function.Predicate<WeatherType> filter) {
    int total = counts.values().stream().mapToInt(Integer::intValue).sum();
    int matching =
        counts.entrySet().stream()
            .filter(e -> filter.test(e.getKey()))
            .mapToInt(Map.Entry::getValue)
            .sum();
    return matching / (double) total;
  }

  @Test
  void sameInputsGiveSameWeatherRegardlessOfQueryOrder() {
    List<Sample> forward = new ArrayList<>();
    for (int i = 0; i < 500; i++) {
      forward.add(WeatherModel.sample(SEED, i * 997L, i * 37, -i * 53, PLAINS, P));
    }
    for (int i = 499; i >= 0; i--) {
      assertEquals(forward.get(i), WeatherModel.sample(SEED, i * 997L, i * 37, -i * 53, PLAINS, P));
    }
  }

  @Test
  void differentSeedsGiveDifferentWeather() {
    int differences = 0;
    for (int i = 0; i < 200; i++) {
      long time = i * 6_000L;
      if (WeatherModel.sample(1L, time, 0, 0, PLAINS, P).state().type()
          != WeatherModel.sample(2L, time, 0, 0, PLAINS, P).state().type()) {
        differences++;
      }
    }
    assertTrue(differences > 10);
  }

  @Test
  void temperateRainFrequencyIsNearVanilla() {
    // Vanilla rains ~16% of the time; drizzle and fog make ours a little wetter.
    double wet = share(histogram(PLAINS, Season.SPRING), WeatherType::isPrecipitation);
    assertTrue(wet > 0.08 && wet < 0.35, "precipitation share " + wet);
  }

  @Test
  void plainsSnowInWinterAndNeverInSummer() {
    Map<WeatherType, Integer> winter = histogram(PLAINS, Season.WINTER);
    double snow = share(winter, WeatherType::isSnow);
    double rain = share(winter, WeatherType::isRain);
    assertTrue(snow > rain, "winter snow " + snow + " vs rain " + rain);
    assertTrue(rain > 0.0, "thaw days bring some winter rain");
    assertEquals(0.0, share(histogram(PLAINS, Season.SUMMER), WeatherType::isSnow));
  }

  @Test
  void taigaHasHarsherWintersThanPlains() {
    double taigaBlizzards = share(histogram(TAIGA, Season.WINTER), t -> t == WeatherType.BLIZZARD);
    double plainsStorms =
        share(
            histogram(PLAINS, Season.WINTER),
            t -> t == WeatherType.BLIZZARD || t == WeatherType.SNOWSTORM);
    assertTrue(taigaBlizzards > plainsStorms, taigaBlizzards + " vs " + plainsStorms);
    Map<WeatherType, Integer> summer = histogram(TAIGA, Season.SUMMER);
    assertTrue(
        share(summer, WeatherType::isRain)
            > share(histogram(PLAINS, Season.SUMMER), WeatherType::isRain));
    assertTrue(share(summer, t -> t == WeatherType.FOG) > 0.05);
  }

  @Test
  void desertIsDryWithSummerSandstormsAndRareWinterSnow() {
    Map<WeatherType, Integer> summer = histogram(DESERT, Season.SUMMER);
    assertTrue(share(summer, t -> t == WeatherType.SANDSTORM) > 0.05);
    assertTrue(share(summer, t -> t == WeatherType.HEAT) > 0.05);
    assertEquals(0.0, share(summer, WeatherType::isSnow));
    assertEquals(0.0, share(summer, t -> t == WeatherType.FOG));

    Map<WeatherType, Integer> winter = histogram(DESERT, Season.WINTER);
    double snow = share(winter, WeatherType::isSnow);
    assertTrue(snow > 0.0 && snow < 0.06, "desert snow should be rare but possible: " + snow);
    assertEquals(0.0, share(winter, t -> t == WeatherType.SANDSTORM));
    assertEquals(0.0, share(winter, WeatherType::isRain));
  }

  @Test
  void biomeConstrainsSpecialWeather() {
    for (Season season : Season.values()) {
      assertEquals(0.0, share(histogram(SAVANNA, season), t -> t == WeatherType.SANDSTORM));
      assertEquals(
          0.0,
          share(
              histogram(PLAINS, season), t -> t == WeatherType.HEAT || t == WeatherType.SANDSTORM));
      if (season != Season.AUTUMN) {
        assertEquals(0.0, share(histogram(FOREST, season), t -> t == WeatherType.LEAF_FALL));
      }
    }
    assertTrue(share(histogram(FOREST, Season.AUTUMN), t -> t == WeatherType.LEAF_FALL) > 0.05);
    assertEquals(0.0, share(histogram(PLAINS, Season.AUTUMN), t -> t == WeatherType.LEAF_FALL));
  }

  @Test
  void heatOnlyByDayAuroraOnlyOnColdWinterNights() {
    for (Season season : Season.values()) {
      for (Climate climate : List.of(PLAINS, TAIGA, DESERT)) {
        long seasonStart =
            season.ordinal() * (long) GuestTime.DAYS_PER_SEASON * GuestTime.TICKS_PER_DAY;
        for (long t = 0; t < GuestTime.DAYS_PER_SEASON * GuestTime.TICKS_PER_DAY; t += 500) {
          long time = seasonStart + t;
          Sample sample = WeatherModel.sample(SEED, time, 1234, 5678, climate, P);
          if (sample.state().type() == WeatherType.HEAT) {
            assertFalse(GuestTime.isNight(time));
          }
          if (sample.state().aurora()) {
            assertEquals(Season.WINTER, season);
            assertTrue(GuestTime.isNight(time));
            assertSame(TAIGA, climate);
          }
        }
      }
    }
    int[] auroraNights = {0};
    forSeason(TAIGA, Season.WINTER, s -> auroraNights[0] += s.state().aurora() ? 1 : 0);
    assertTrue(auroraNights[0] > 0, "aurora must be possible");
  }

  @Test
  void weatherChangesSmoothlyAcrossRegions() {
    for (int t = 0; t < 40; t++) {
      long time = t * 11_000L;
      Sample previous = null;
      for (int x = -3_000; x < 3_000; x += 4) {
        Sample current = WeatherModel.sample(SEED, time, x, 777, PLAINS, P);
        if (previous != null
            && previous.state().type().isPrecipitation()
            && current.state().type().isPrecipitation()) {
          assertTrue(
              Math.abs(previous.state().intensity() - current.state().intensity()) < 0.05,
              "intensity jump at x=" + x);
        }
        previous = current;
      }
    }
  }
}
