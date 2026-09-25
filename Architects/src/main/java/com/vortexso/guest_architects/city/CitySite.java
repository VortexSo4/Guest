package com.vortexso.guest_architects.city;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.FeaturePoolElement;
import net.minecraft.world.phys.AABB;

/**
 * Runtime view of one ancient city: geometry read from the structure start (a cache, rebuilt on
 * every discovery) plus observation state. Persistent facts live in {@link CityRecord}.
 */
public final class CitySite {
  /** Sculk may lap slightly over the ritual room's walls without counting as escaped. */
  private static final int RITUAL_MARGIN = 3;

  public final long id;
  public final BoundingBox box;
  public final BoundingBox ritualBox;
  public final BoundingBox portalBox;

  /** Positions of the vanilla sculk patches (catalyst origins) placed by the city jigsaw. */
  public final List<BlockPos> origins;

  public final CityRecord record;

  int observedSeconds;
  boolean activated;
  int scanCursor;
  final SequencedSet<BlockPos> strays = new LinkedHashSet<>();
  List<com.vortexso.guest_architects.entity.Architect> architects = List.of();
  CityLife.Profile profile;
  int population;

  private CitySite(
      long id,
      BoundingBox box,
      BoundingBox ritualBox,
      BoundingBox portalBox,
      List<BlockPos> origins,
      CityRecord record) {
    this.id = id;
    this.box = box;
    this.ritualBox = ritualBox;
    this.portalBox = portalBox;
    this.origins = origins;
    this.record = record;
  }

  /**
   * The ritual room is the building holding the most sculk patches: that is where vanilla
   * generation already concentrates sculk, so the living city simply keeps it there.
   */
  static CitySite from(long id, StructureStart start, CityRecord record) {
    BoundingBox box = start.getBoundingBox();
    List<BlockPos> origins = new ArrayList<>();
    List<BoundingBox> buildings = new ArrayList<>();
    BoundingBox portal = null;

    for (StructurePiece piece : start.getPieces()) {
      if (!(piece instanceof PoolElementStructurePiece element)) {
        continue;
      }
      if (element.getElement() instanceof FeaturePoolElement) {
        origins.add(element.getPosition().immutable());
        continue;
      }
      String name = element.getElement().toString();
      if (name.contains("ancient_city/city_center/city_center_")) {
        portal = piece.getBoundingBox();
      } else if (name.contains("ancient_city/structures/")) {
        buildings.add(piece.getBoundingBox());
      }
    }
    if (portal == null) {
      BlockPos c = box.getCenter();
      portal =
          new BoundingBox(
              c.getX() - 8, c.getY(), c.getZ() - 8, c.getX() + 8, c.getY() + 8, c.getZ() + 8);
    }

    BoundingBox ritual = portal;
    long best = 0;
    for (BoundingBox building : buildings) {
      BoundingBox area = building.inflatedBy(2);
      long count = origins.stream().filter(area::isInside).count();
      if (count > best) {
        best = count;
        ritual = building;
      }
    }
    return new CitySite(id, box, ritual, portal, List.copyOf(origins), record);
  }

  public BlockPos center() {
    return box.getCenter();
  }

  public boolean inRitualArea(BlockPos pos) {
    return ritualBox.inflatedBy(RITUAL_MARGIN).isInside(pos);
  }

  public boolean contains(BlockPos pos) {
    return box.inflatedBy(4).isInside(pos);
  }

  public AABB aabb() {
    return AABB.of(box).inflate(8);
  }

  public boolean living() {
    return population > 0;
  }

  public int population() {
    return population;
  }

  public CityLife.Profile profile() {
    return profile;
  }

  public boolean activated() {
    return activated;
  }

  public List<com.vortexso.guest_architects.entity.Architect> architects() {
    return architects;
  }

  public int strayCount() {
    return strays.size();
  }
}
