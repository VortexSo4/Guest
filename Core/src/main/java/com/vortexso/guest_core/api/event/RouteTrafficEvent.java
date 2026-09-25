package com.vortexso.guest_core.api.event;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;

/**
 * Aggregate movement along a route that nobody walked concretely (the area was unobserved).
 * Emitters describe usage; whoever owns surface wear decides what it does to blocks. One event can
 * stand for many trips over a long interval, so consumers must scale by {@link #trips()}.
 *
 * <p>Posted on {@code NeoForge.EVENT_BUS}.
 */
public final class RouteTrafficEvent extends Event {
  private final ServerLevel level;
  private final BlockPos from;
  private final BlockPos to;
  private final double trips;
  private final Identifier traveler;

  public RouteTrafficEvent(
      ServerLevel level, BlockPos from, BlockPos to, double trips, Identifier traveler) {
    this.level = level;
    this.from = from;
    this.to = to;
    this.trips = trips;
    this.traveler = traveler;
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

  /** Expected number of one-way trips; fractional values are meaningful. */
  public double trips() {
    return trips;
  }

  /** Who travelled, e.g. {@code guest_settlements:caravan}; lets consumers weight wear. */
  public Identifier traveler() {
    return traveler;
  }
}
