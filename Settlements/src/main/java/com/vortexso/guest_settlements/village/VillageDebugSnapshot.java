package com.vortexso.guest_settlements.village;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;

public record VillageDebugSnapshot(
        List<VillageSnapshot> villages,
        List<RoadSnapshot> roads
) {
    public record VillageSnapshot(
            long id,
            BlockPos center,
            boolean loaded,
            BoundingBox structureBox,
            List<FarmSnapshot> farms
    ) {
    }

    public record FarmSnapshot(
            BoundingBox pieceBox,
            BoundingBox farmBox,
            int farmlandAmount,
            boolean complete
    ) {
    }

    public record RoadSnapshot(
            long firstVillageId,
            long secondVillageId,
            BlockPos firstCenter,
            BlockPos secondCenter
    ) {
    }
}
