package com.vortexso.guest_settlements.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;
import net.minecraft.resources.Identifier;

/**
 * Persistent aggregate state of one village: only what cannot be re-derived while the village is
 * unloaded. {@code beds} and {@code farmland} are the last observation of the built village; they
 * are refreshed from the world on every load, the simulation just needs them in between.
 *
 * @param day last simulated day
 * @param infected zombie villagers left behind when the village fell
 * @param lastDeathDay drives new-moon mourning; {@link Long#MIN_VALUE} when nobody died yet
 */
public record VillageState(
    long id,
    long day,
    VillagePopulation villagePopulation,
    int beds,
    int farmland,
    double foodReserve,
    boolean fallen,
    int infected,
    long lastDeathDay) {

  private static final Codec<VillagePopulation> POPULATION_CODEC =
      RecordCodecBuilder.create(
          i ->
              i.group(
                      Codec.INT.fieldOf("children").forGetter(VillagePopulation::children),
                      Codec.unboundedMap(Identifier.CODEC, Codec.INT)
                          .fieldOf("professions")
                          .forGetter(VillagePopulation::professions))
                  .apply(i, VillagePopulation::new));

  public static final Codec<VillageState> CODEC =
      RecordCodecBuilder.create(
          i ->
              i.group(
                      Codec.LONG.fieldOf("id").forGetter(VillageState::id),
                      Codec.LONG.fieldOf("day").forGetter(VillageState::day),
                      POPULATION_CODEC
                          .fieldOf("population")
                          .forGetter(VillageState::villagePopulation),
                      Codec.INT.fieldOf("beds").forGetter(VillageState::beds),
                      Codec.INT.fieldOf("farmland").forGetter(VillageState::farmland),
                      Codec.DOUBLE.fieldOf("food").forGetter(VillageState::foodReserve),
                      Codec.BOOL.optionalFieldOf("fallen", false).forGetter(VillageState::fallen),
                      Codec.INT.optionalFieldOf("infected", 0).forGetter(VillageState::infected),
                      Codec.LONG
                          .optionalFieldOf("last_death_day", Long.MIN_VALUE)
                          .forGetter(VillageState::lastDeathDay))
                  .apply(i, VillageState::new));

  public VillageState {
    if (villagePopulation == null) {
      throw new IllegalArgumentException("population must not be null");
    }
    if (beds < 0 || farmland < 0 || infected < 0) {
      throw new IllegalArgumentException("beds, farmland and infected must be >= 0");
    }
    if (!Double.isFinite(foodReserve) || foodReserve < 0.0) {
      throw new IllegalArgumentException("foodReserve must be finite and >= 0");
    }
  }

  /** A freshly observed village: no past to simulate yet. */
  public static VillageState observed(
      long id, long day, VillagePopulation population, int beds, int farmland) {
    return new VillageState(id, day, population, beds, farmland, 0.0, false, 0, Long.MIN_VALUE);
  }

  public int adults() {
    return villagePopulation.adults();
  }

  public int children() {
    return villagePopulation.children();
  }

  public int population() {
    return villagePopulation.population();
  }

  public int freeHousing() {
    return Math.max(0, beds - population());
  }

  public VillageState withDay(long day) {
    return new VillageState(
        id, day, villagePopulation, beds, farmland, foodReserve, fallen, infected, lastDeathDay);
  }

  public VillageState withObservation(VillagePopulation population, int beds, int farmland) {
    return new VillageState(
        id, day, population, beds, farmland, foodReserve, fallen, infected, lastDeathDay);
  }

  public VillageState withFallen(boolean fallen, int infected) {
    return new VillageState(
        id, day, villagePopulation, beds, farmland, foodReserve, fallen, infected, lastDeathDay);
  }

  public VillageState withLastDeathDay(long lastDeathDay) {
    return new VillageState(
        id, day, villagePopulation, beds, farmland, foodReserve, fallen, infected, lastDeathDay);
  }

  public Map<Identifier, Integer> professions() {
    return villagePopulation.professions();
  }
}
