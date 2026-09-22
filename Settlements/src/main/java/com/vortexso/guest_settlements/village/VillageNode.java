package com.vortexso.guest_settlements.village;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

public final class VillageNode {
    private final long id;
    private BlockPos center;
    private BoundingBox structureBox;
    private boolean loaded;

    private final List<VillageFarmRegion> farmRegions = new ArrayList<>();

    public VillageNode(long id, BlockPos center) {
        this.id = id;
        this.center = center;
    }

    public long id() {
        return id;
    }

    public BlockPos center() {
        return center;
    }

    public BoundingBox structureBox() {
        return structureBox;
    }

    public boolean loaded() {
        return loaded;
    }

    public List<VillageFarmRegion> farmRegions() {
        return farmRegions;
    }

    public void markLoaded(BlockPos center, BoundingBox structureBox) {
        this.center = center;
        this.structureBox = structureBox;
        this.loaded = true;
    }

    public void markUnloaded() {
        loaded = false;
    }

    public void replaceFarmRegions(List<VillageFarmRegion> regions) {
        farmRegions.clear();
        farmRegions.addAll(regions);
    }
}
