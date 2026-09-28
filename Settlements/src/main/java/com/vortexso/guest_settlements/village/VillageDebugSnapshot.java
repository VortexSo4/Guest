package com.vortexso.guest_settlements.village;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public record VillageDebugSnapshot(
    long day,
    List<VillageSnapshot> villages,
    List<RoadSnapshot> roads,
    List<CaravanSnapshot> caravans) {
  public record VillageSnapshot(
      long id,
      BlockPos center,
      boolean active,
      @Nullable BoundingBox structureBox,
      @Nullable BlockPos bell,
      @Nullable VillageState state,
      VillageSimulator.@Nullable Rates rates,
      double pressure,
      List<FarmSnapshot> farms) {}

  public record FarmSnapshot(
      BoundingBox pieceBox, @Nullable BoundingBox farmBox, int farmlandAmount, boolean complete) {}

  public record RoadSnapshot(
      BlockPos firstCenter, BlockPos secondCenter, List<BlockPos> road, boolean abandoned) {}

  public record CaravanSnapshot(Vec3 position, double progress, boolean lost) {}
}
