package com.vortexso.guest_wilds.path;

import com.vortexso.guest_core.api.GuestHash;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;

public final class Route {

  private static final double STEP = 0.5;

  private Route() {}

  public record Cell(int x, int z, double weight) {}

  public static List<Cell> strip(List<BlockPos> points, int width, double verge) {
    Map<Long, Cell> cells = new LinkedHashMap<>();
    double half = (Math.max(1, width) - 1) / 2.0;
    for (int i = 0; i + 1 < points.size(); i++) {
      BlockPos a = points.get(i);
      BlockPos b = points.get(i + 1);
      double dx = b.getX() - a.getX();
      double dz = b.getZ() - a.getZ();
      double length = Math.sqrt(dx * dx + dz * dz);
      int steps = Math.max(1, (int) Math.ceil(length / STEP));
      double nx = length < 1e-6 ? 0.0 : -dz / length;
      double nz = length < 1e-6 ? 0.0 : dx / length;
      for (int s = 0; s <= steps; s++) {
        double t = s / (double) steps;
        double px = a.getX() + dx * t;
        double pz = a.getZ() + dz * t;
        for (double o = -half - 1.0; o <= half + 1.0 + 1e-9; o += STEP) {
          double weight = Math.abs(o) <= half + 1e-9 ? 1.0 : verge;
          if (weight <= 0.0) {
            continue;
          }

          int x = (int) Math.floor(px + 0.5 + nx * o);
          int z = (int) Math.floor(pz + 0.5 + nz * o);
          long key = (x & 0xFFFFFFFFL) | ((long) z << 32);
          Cell old = cells.get(key);
          if (old == null || old.weight() < weight) {
            cells.put(key, new Cell(x, z, weight));
          }
        }
      }
    }
    return new ArrayList<>(cells.values());
  }

  public static List<BlockPos> meander(
      long seed, BlockPos from, BlockPos to, double amplitude, double wavelength) {
    double dx = to.getX() - from.getX();
    double dz = to.getZ() - from.getZ();
    double length = Math.sqrt(dx * dx + dz * dz);
    if (length < 1.0) {
      return List.of(from, to);
    }
    long a = Math.min(from.asLong(), to.asLong());
    long b = Math.max(from.asLong(), to.asLong());
    double phase = GuestHash.unit(GuestHash.hash(seed, a, b)) * Math.PI * 2.0;
    double nx = -dz / length;
    double nz = dx / length;
    int steps = (int) Math.ceil(length);
    List<BlockPos> points = new ArrayList<>(steps + 1);
    for (int i = 0; i <= steps; i++) {
      double t = i / (double) steps;
      double offset =
          amplitude * Math.sin(phase + i * Math.PI * 2.0 / wavelength) * Math.sin(Math.PI * t);
      points.add(
          new BlockPos(
              (int) Math.round(from.getX() + dx * t + nx * offset),
              0,
              (int) Math.round(from.getZ() + dz * t + nz * offset)));
    }
    return points;
  }
}
