package com.vortexso.guest_settlements.village;

import com.vortexso.guest_core.api.GuestHash;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;

public final class Caravans {
  private static final long EVENT_DEPART = 0xCA7A7A7L;
  private static final long EVENT_DIRECTION = 0xD1EL;
  private static final long EVENT_LOSS = 0x1055L;
  private static final long EVENT_LOSS_AT = 0x1055A7L;

  private Caravans() {}

  public record Parameters(
      double departureChancePerDay,
      double blocksPerDay,
      double lossChance,
      double severeLossChance,
      double cargoFood) {}

  public record Caravan(
      long id,
      RoadEdge edge,
      long fromId,
      long toId,
      long departDay,
      long arriveDay,
      boolean lost,
      double lossAt) {
    public double progress(double day) {
      return Math.max(0.0, Math.min(1.0, (day - departDay) / (double) (arriveDay - departDay)));
    }

    public double lossDay() {
      return departDay + lossAt * (arriveDay - departDay);
    }

    public Caravan withLost(boolean lost) {
      return new Caravan(id, edge, fromId, toId, departDay, arriveDay, lost, lossAt);
    }
  }

  public static int travelDays(double distance, Parameters parameters) {
    return Math.max(1, (int) Math.ceil(distance / Math.max(1.0, parameters.blocksPerDay())));
  }

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
              hash,
              edge,
              forward ? edge.firstVillageId() : edge.secondVillageId(),
              forward ? edge.secondVillageId() : edge.firstVillageId(),
              day,
              day + travel,
              GuestHash.unit(GuestHash.hash(hash, EVENT_LOSS)) < loss,
              0.2 + 0.6 * GuestHash.unit(GuestHash.hash(hash, EVENT_LOSS_AT))));
    }
    return result;
  }

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
