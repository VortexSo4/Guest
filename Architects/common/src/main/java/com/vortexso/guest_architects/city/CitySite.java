package com.vortexso.guest_architects.city;

import com.vortexso.guest_architects.entity.Architect;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.FeaturePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.ListPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

public final class CitySite {

  private static final int RITUAL_MARGIN = 3;

  public final long id;

  public final boolean livingStructure;

  public final BoundingBox box;

  public final BoundingBox ritualBox;

  public final List<BoundingBox> ritualBoxes;

  public final BoundingBox portalBox;

  public final List<BlockPos> origins;

  public final List<BlockPos> ritualOrigins;

  public final List<Piece> pieces;

  public final List<BoundingBox> quietBuildings;

  public final BlockPos referencePos;

  public final CityRecord record;

  int observedSeconds;
  boolean activated;
  int scanCursor;
  boolean conversionDone;

  boolean piecesDone;

  boolean memorySearched;
  final SequencedSet<BlockPos> strays = new LinkedHashSet<>();
  List<Architect> architects = List.of();
  CityLife.Profile profile;
  int population;
  MemoryPlace.@Nullable Markers memory;
  int shownNames = -1;

  List<BlockPos> sculkSpots = List.of();

  List<BlockPos> frameSpots = List.of();
  List<BlockPos> woolSpots = List.of();
  int shownLight = -1;

  public record Piece(int index, PoolElementStructurePiece piece, List<String> templates) {
    public BoundingBox box() {
      return piece.getBoundingBox();
    }
  }

  private CitySite(
      long id,
      boolean livingStructure,
      BoundingBox box,
      BoundingBox ritualBox,
      List<BoundingBox> ritualBoxes,
      BoundingBox portalBox,
      List<BlockPos> origins,
      List<BlockPos> ritualOrigins,
      List<Piece> pieces,
      List<BoundingBox> quietBuildings,
      BlockPos referencePos,
      CityRecord record) {
    this.id = id;
    this.livingStructure = livingStructure;
    this.box = box;
    this.ritualBox = ritualBox;
    this.ritualBoxes = ritualBoxes;
    this.portalBox = portalBox;
    this.origins = origins;
    this.ritualOrigins = ritualOrigins;
    this.pieces = pieces;
    this.quietBuildings = quietBuildings;
    this.referencePos = referencePos;
    this.record = record;
  }

  static CitySite from(
      long id, StructureStart start, boolean living, CityRecord record, ServerLevel level) {
    BoundingBox box = start.getBoundingBox();
    StructureTemplateManager templateManager = level.getStructureManager();
    List<BlockPos> origins = new ArrayList<>();
    List<BlockPos> catalysts = new ArrayList<>();
    List<Piece> pieces = new ArrayList<>();
    List<BoundingBox> buildings = new ArrayList<>();
    List<BoundingBox> ritualBoxes = new ArrayList<>();
    BoundingBox portal = null;

    List<StructurePiece> all = start.getPieces();
    for (int i = 0; i < all.size(); i++) {
      if (!(all.get(i) instanceof PoolElementStructurePiece element)) {
        continue;
      }
      if (element.getElement() instanceof FeaturePoolElement) {
        origins.add(element.getPosition().immutable());
        continue;
      }
      List<String> templates = templates(element.getElement());
      if (templates.isEmpty()) {
        continue;
      }
      pieces.add(new Piece(i, element, templates));
      if (living) {
        List<BlockPos> found = catalysts(templateManager, element, templates);
        if (!found.isEmpty()) {
          catalysts.addAll(found);
          ritualBoxes.add(element.getBoundingBox());
        }
      }
      String path = templates.getFirst();
      if (path.contains("ancient_city/city_center/city_center_")) {
        portal = element.getBoundingBox();
      } else if (path.contains("ancient_city/structures/")) {
        buildings.add(element.getBoundingBox());
      }
    }
    BlockPos center = box.getCenter();
    if (portal == null) {
      portal =
          new BoundingBox(
              center.getX() - 8,
              center.getY(),
              center.getZ() - 8,
              center.getX() + 8,
              center.getY() + 8,
              center.getZ() + 8);
    }
    BlockPos portalCenter = portal.getCenter();
    Comparator<BoundingBox> byDistance =
        Comparator.comparingDouble(b -> b.getCenter().distSqr(portalCenter));

    List<BlockPos> ritualOrigins;
    if (living) {
      ritualOrigins = List.copyOf(catalysts);
    } else {
      buildings.stream()
          .max(Comparator.comparingLong(b -> countInside(b, origins)))
          .filter(b -> countInside(b, origins) > 0)
          .ifPresent(ritualBoxes::add);
      ritualOrigins =
          origins.stream()
              .filter(o -> ritualBoxes.stream().anyMatch(b -> b.inflatedBy(2).isInside(o)))
              .toList();
    }
    buildings.removeAll(ritualBoxes);
    ritualBoxes.sort(byDistance);
    buildings.sort(byDistance);
    BoundingBox ritual = ritualBoxes.isEmpty() ? portal : ritualBoxes.getFirst();

    BoundingBox first = all.getFirst().getBoundingBox();
    BlockPos reference =
        new BlockPos(first.getCenter().getX(), first.minY(), first.getCenter().getZ());
    return new CitySite(
        id,
        living,
        box,
        ritual,
        List.copyOf(ritualBoxes),
        portal,
        List.copyOf(origins),
        ritualOrigins,
        List.copyOf(pieces),
        List.copyOf(buildings),
        reference,
        record);
  }

  private static List<BlockPos> catalysts(
      StructureTemplateManager manager, PoolElementStructurePiece piece, List<String> templates) {
    StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(piece.getRotation());
    List<BlockPos> found = new ArrayList<>();
    for (String template : templates) {
      manager
          .getOrCreate(Identifier.parse(template))
          .filterBlocks(piece.getPosition(), settings, Blocks.SCULK_CATALYST)
          .forEach(info -> found.add(info.pos()));
    }
    return found;
  }

  static List<String> templates(StructurePoolElement element) {
    return switch (element) {
      case SinglePoolElement single -> List.of(single.getTemplateLocation().toString());
      case ListPoolElement list ->
          list.getElements().stream().flatMap(e -> templates(e).stream()).toList();
      default -> List.of();
    };
  }

  private static long countInside(BoundingBox box, List<BlockPos> points) {
    BoundingBox area = box.inflatedBy(2);
    return points.stream().filter(area::isInside).count();
  }

  public BlockPos center() {
    return box.getCenter();
  }

  public boolean inRitualArea(BlockPos pos) {
    for (BoundingBox ritual : ritualBoxes) {
      if (ritual.inflatedBy(RITUAL_MARGIN).isInside(pos)) {
        return true;
      }
    }
    return false;
  }

  public boolean inMemoryPlace(BlockPos pos) {
    return memory != null && memory.box().isInside(pos);
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

  public boolean extinct() {
    return livingStructure && profile != null && population == 0;
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

  public List<Architect> architects() {
    return architects;
  }

  public int strayCount() {
    return strays.size();
  }

  public MemoryPlace.@Nullable Markers memory() {
    return memory;
  }
}
