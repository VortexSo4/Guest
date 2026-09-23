package com.vortexso.guest_settlements.village;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public final class VillageNode {
  private final long id;
  private BlockPos center;
  private BoundingBox structureBox;

  private final Set<Long> loadedChunks = new HashSet<>();

  private VillageState state;

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
    return !loadedChunks.isEmpty();
  }

  public VillageState state() {
    return state;
  }

  public List<VillageFarmRegion> farmRegions() {
    return farmRegions;
  }

  public void markLoaded(BlockPos center, BoundingBox structureBox) {
    this.center = center;
    this.structureBox = structureBox;
  }

  public void markChunkLoaded(ChunkPos chunkPos) {
    loadedChunks.add(chunkPos.pack());
  }

  public void markChunkUnloaded(ChunkPos chunkPos) {
    loadedChunks.remove(chunkPos.pack());
  }

  public void clearLoadedChunks() {
    loadedChunks.clear();
  }

  public void updateState(VillageState state) {
    this.state = state;
  }

  public void replaceFarmRegions(List<VillageFarmRegion> regions) {
    farmRegions.clear();
    farmRegions.addAll(regions);
  }
}
