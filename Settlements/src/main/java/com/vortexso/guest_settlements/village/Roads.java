package com.vortexso.guest_settlements.village;

import com.vortexso.guest_core.api.event.RouteTrafficEvent;
import com.vortexso.guest_settlements.GuestSettlements;
import com.vortexso.guest_settlements.SettlementsConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;

public final class Roads {

  private static final int DEFAULT_VILLAGE_RADIUS = 40;

  private Roads() {}

  public static BlockPos anchor(VillageNode node) {
    return BlockPos.of(node.id());
  }

  public static List<BlockPos> betweenVillages(
      List<BlockPos> route, VillageNode from, VillageNode to) {
    List<BlockPos> trimmed = clipStart(route, extent(from));
    return clipStart(trimmed.reversed(), extent(to)).reversed();
  }

  public static void postTraffic(
      ServerLevel level, VillageNode from, VillageNode to, double trips, Identifier traveler) {
    RoutePlanner.whenPlanned(
        level,
        anchor(from),
        anchor(to),
        route -> {
          List<BlockPos> road = betweenVillages(route, from, to);
          if (road.size() >= 2) {
            int width = SettlementsConfig.value(SettlementsConfig.ROAD_WIDTH);
            NeoForge.EVENT_BUS.post(
                new RouteTrafficEvent(
                    level, road.getFirst(), road.getLast(), trips, traveler, road, width));
            GuestSettlements.LOGGER.debug(
                "Road traffic {} -> {}: {} trips, {} waypoints, width {}",
                road.getFirst(),
                road.getLast(),
                trips,
                road.size(),
                width);
          }
        });
  }

  public static boolean abandoned(VillageNode first, VillageNode second) {
    return gone(first) || gone(second);
  }

  private static boolean gone(VillageNode node) {
    VillageState state = node.state();
    return state != null && (state.fallen() || state.population() == 0);
  }

  private static BoundingBox extent(VillageNode node) {
    BoundingBox box = node.structureBox();
    BlockPos center = node.center();
    return box != null
        ? box
        : BoundingBox.fromCorners(
            center.offset(-DEFAULT_VILLAGE_RADIUS, 0, -DEFAULT_VILLAGE_RADIUS),
            center.offset(DEFAULT_VILLAGE_RADIUS, 0, DEFAULT_VILLAGE_RADIUS));
  }

  private static List<BlockPos> clipStart(List<BlockPos> route, BoundingBox box) {
    int first = 0;
    while (first < route.size() && inside(box, route.get(first))) {
      first++;
    }
    if (first == 0 || first == route.size()) {
      return first == 0 ? route : List.of(route.getLast());
    }
    BlockPos inside = route.get(first - 1);
    BlockPos outside = route.get(first);
    int steps = (int) Math.ceil(horizontal(inside, outside));
    BlockPos exit = outside;
    for (int s = 1; s <= steps; s++) {
      BlockPos point = lerp(inside, outside, s / (double) steps);
      if (!inside(box, point)) {
        exit = point;
        break;
      }
    }
    List<BlockPos> result = new ArrayList<>(route.size() - first + 1);
    result.add(exit);
    result.addAll(route.subList(first, route.size()));
    if (result.size() > 1 && result.get(0).equals(result.get(1))) {
      result.removeFirst();
    }
    return List.copyOf(result);
  }

  public static double length(List<BlockPos> route) {
    double total = 0.0;
    for (int k = 0; k + 1 < route.size(); k++) {
      total += horizontal(route.get(k), route.get(k + 1));
    }
    return total;
  }

  public static Vec3 pointAt(List<BlockPos> route, double fraction) {
    double remaining = Math.max(0.0, Math.min(1.0, fraction)) * length(route);
    for (int k = 0; k + 1 < route.size(); k++) {
      BlockPos a = route.get(k);
      BlockPos b = route.get(k + 1);
      double segment = horizontal(a, b);
      if (remaining <= segment && segment > 0.0) {
        double t = remaining / segment;
        return Vec3.atBottomCenterOf(a).lerp(Vec3.atBottomCenterOf(b), t);
      }
      remaining -= segment;
    }
    return Vec3.atBottomCenterOf(route.getLast());
  }

  private static boolean inside(BoundingBox box, BlockPos pos) {
    return pos.getX() >= box.minX()
        && pos.getX() <= box.maxX()
        && pos.getZ() >= box.minZ()
        && pos.getZ() <= box.maxZ();
  }

  private static double horizontal(BlockPos a, BlockPos b) {
    return Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
  }

  private static BlockPos lerp(BlockPos a, BlockPos b, double t) {
    return BlockPos.containing(
        a.getX() + (b.getX() - a.getX()) * t,
        a.getY() + (b.getY() - a.getY()) * t,
        a.getZ() + (b.getZ() - a.getZ()) * t);
  }
}
