package com.vortexso.guest_core.api.event;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;

public final class RouteTrafficEvent extends Event {
  private final ServerLevel level;
  private final BlockPos from;
  private final BlockPos to;
  private final double trips;
  private final Identifier traveler;
  private final List<BlockPos> waypoints;
  private final int width;

  public RouteTrafficEvent(
      ServerLevel level, BlockPos from, BlockPos to, double trips, Identifier traveler) {
    this(level, from, to, trips, traveler, List.of(), 1);
  }

  public RouteTrafficEvent(
      ServerLevel level,
      BlockPos from,
      BlockPos to,
      double trips,
      Identifier traveler,
      List<BlockPos> waypoints,
      int width) {
    this.level = level;
    this.from = from;
    this.to = to;
    this.trips = trips;
    this.traveler = traveler;
    this.waypoints = List.copyOf(waypoints);
    this.width = Math.max(1, width);
  }

  public ServerLevel level() {
    return level;
  }

  public BlockPos from() {
    return from;
  }

  public BlockPos to() {
    return to;
  }

  public double trips() {
    return trips;
  }

  public Identifier traveler() {
    return traveler;
  }

  public List<BlockPos> waypoints() {
    return waypoints;
  }

  public int width() {
    return width;
  }
}
