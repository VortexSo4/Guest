package com.vortexso.guest_settlements.village;

import net.minecraft.world.level.levelgen.structure.BoundingBox;

public final class VillageFarmRegion {
  private final long villageId;
  private final BoundingBox pieceBox;

  private BoundingBox farmBox;
  private int farmlandAmount;
  private boolean complete;
  private boolean dirty = true;

  public VillageFarmRegion(long villageId, BoundingBox pieceBox) {
    this.villageId = villageId;
    this.pieceBox = pieceBox;
  }

  public long villageId() {
    return villageId;
  }

  public BoundingBox pieceBox() {
    return pieceBox;
  }

  public BoundingBox farmBox() {
    return farmBox;
  }

  public int farmlandAmount() {
    return farmlandAmount;
  }

  public boolean complete() {
    return complete;
  }

  public boolean dirty() {
    return dirty;
  }

  public void markDirty() {
    dirty = true;
  }

  public void update(VillageFarmScanner.ScanResult result) {
    farmlandAmount = result.farmlandAmount();
    farmBox = result.farmBox();
    complete = result.complete();
    dirty = false;
  }
}
