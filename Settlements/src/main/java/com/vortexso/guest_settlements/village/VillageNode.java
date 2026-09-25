package com.vortexso.guest_settlements.village;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * One village. {@code id}, {@code center} and {@link #state()} are persisted by {@link
 * VillageWorldData}; everything else is a disposable view of the loaded world.
 */
public final class VillageNode {
  private final long id;
  private BlockPos center;
  private @Nullable VillageState state;

  private @Nullable BoundingBox structureBox;
  private final Set<Long> loadedChunks = new HashSet<>();
  private int totalChunks;
  private final List<VillageFarmRegion> farmRegions = new ArrayList<>();
  private @Nullable BlockPos bell;

  public VillageNode(long id, BlockPos center, @Nullable VillageState state) {
    this.id = id;
    this.center = center;
    this.state = state;
  }

  public long id() {
    return id;
  }

  public BlockPos center() {
    return center;
  }

  public @Nullable BoundingBox structureBox() {
    return structureBox;
  }

  /**
   * Fully loaded: every chunk of the structure is present, so a villager scan sees everyone.
   * Partially loaded villages are treated as unobserved.
   */
  public boolean loaded() {
    return structureBox != null && totalChunks > 0 && loadedChunks.size() >= totalChunks;
  }

  public @Nullable VillageState state() {
    return state;
  }

  public List<VillageFarmRegion> farmRegions() {
    return farmRegions;
  }

  public int farmland() {
    int total = 0;
    for (VillageFarmRegion region : farmRegions) {
      total += region.farmlandAmount();
    }
    return total;
  }

  public @Nullable BlockPos bell() {
    return bell;
  }

  public void setBell(@Nullable BlockPos bell) {
    this.bell = bell;
  }

  public void setStructure(BlockPos center, BoundingBox structureBox) {
    this.center = center;
    this.structureBox = structureBox;
    this.totalChunks = (int) structureBox.intersectingChunks().count();
  }

  public boolean intersects(ChunkPos chunkPos) {
    return structureBox != null
        && chunkPos.x() >= structureBox.minX() >> 4
        && chunkPos.x() <= structureBox.maxX() >> 4
        && chunkPos.z() >= structureBox.minZ() >> 4
        && chunkPos.z() <= structureBox.maxZ() >> 4;
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
