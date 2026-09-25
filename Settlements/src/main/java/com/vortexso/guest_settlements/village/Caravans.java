package com.vortexso.guest_settlements.village;

import com.vortexso.guest_core.api.GuestHash;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;

/**
 * Caravans along roads as a deterministic aggregate process: whether a caravan leaves on a day,
 * which way it goes and whether it arrives are functions of {@code seed + edge + day}, so any
 * window of the past can be recomputed instead of stored. Only severe weather is read from the
 * world (through the predicate), which keeps the result deterministic when Atmosphere is present.
 */
public final class Caravans {
  private static final long EVENT_DEPART = 0xCA7A7A7L;
  private static final long EVENT_DIRECTION = 0xD1EL;
  private static final long EVENT_LOSS = 0x1055L;

  private Caravans() {}

  public record Parameters(
      double departureChancePerDay,
      double blocksPerDay,
      double lossChance,
      double severeLossChance,
      double cargoFood) {}

  /**
   * @param lost the cargo never arrived; {@code arriveDay} is when it would have
   */
  public record Caravan(
      RoadEdge edge, long fromId, long toId, long departDay, long arriveDay, boolean lost) {
    public double progress(double day) {
      return Math.max(0.0, Math.min(1.0, (day - departDay) / (double) (arriveDay - departDay)));
    }
  }

  public static int travelDays(double distance, Parameters parameters) {
    return Math.max(1, (int) Math.ceil(distance / Math.max(1.0, parameters.blocksPerDay())));
  }

  /** Caravans departing in {@code [fromDay, toDay)}. Nobody sets out into severe weather. */
  public static List<Caravan> departures(
      long seed,
      RoadEdge edge,
      double distance,
      long fromDay,
      long toDay,
      Parameters parameters,
      LongPredicate severeOnDay) {
    List<Caravan> result = new ArrayList<>();
    int travel = travelDays(distance, parameters);
    for (long day = fromDay; day < toDay; day++) {
      long hash = GuestHash.hash(seed, edge.firstVillageId(), edge.secondVillageId(), day);
      if (GuestHash.unit(GuestHash.hash(hash, EVENT_DEPART)) >= parameters.departureChancePerDay()
          || severeOnDay.test(day)) {
        continue;
      }
      boolean forward = GuestHash.unit(GuestHash.hash(hash, EVENT_DIRECTION)) < 0.5;
      double loss =
          parameters.lossChance()
              + (severeOnDay.test(day + travel / 2) ? parameters.severeLossChance() : 0.0);
      result.add(
          new Caravan(
              edge,
              forward ? edge.firstVillageId() : edge.secondVillageId(),
              forward ? edge.secondVillageId() : edge.firstVillageId(),
              day,
              day + travel,
              GuestHash.unit(GuestHash.hash(hash, EVENT_LOSS)) < loss));
    }
    return result;
  }

  /** Caravans (lost or not) whose arrival day falls in {@code [fromDay, toDay)}. */
  public static List<Caravan> arrivals(
      long seed,
      RoadEdge edge,
      double distance,
      long fromDay,
      long toDay,
      Parameters parameters,
      LongPredicate severeOnDay) {
    int travel = travelDays(distance, parameters);
    return departures(
        seed, edge, distance, fromDay - travel, toDay - travel, parameters, severeOnDay);
  }
}
