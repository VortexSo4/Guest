package com.vortexso.guest_settlements.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.vortexso.guest_settlements.GuestSettlements;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Per-dimension persistent village state: the known villages with their aggregate state and the
 * roads between them. Roads are stored because finding neighbours needs expensive structure
 * searches, and caravans are derived from them. Farm regions, loaded chunks and bells are rebuilt
 * from the world and never saved.
 */
public final class VillageWorldData extends SavedData {
  private record StoredNode(long id, BlockPos center, Optional<VillageState> state) {
    static final Codec<StoredNode> CODEC =
        RecordCodecBuilder.create(
            i ->
                i.group(
                        Codec.LONG.fieldOf("id").forGetter(StoredNode::id),
                        BlockPos.CODEC.fieldOf("center").forGetter(StoredNode::center),
                        VillageState.CODEC.optionalFieldOf("state").forGetter(StoredNode::state))
                    .apply(i, StoredNode::new));
  }

  private static final Codec<RoadEdge> ROAD_CODEC =
      RecordCodecBuilder.create(
          i ->
              i.group(
                      Codec.LONG.fieldOf("a").forGetter(RoadEdge::firstVillageId),
                      Codec.LONG.fieldOf("b").forGetter(RoadEdge::secondVillageId))
                  .apply(i, RoadEdge::new));

  public static final Codec<VillageWorldData> CODEC =
      RecordCodecBuilder.create(
          i ->
              i.group(
                      StoredNode.CODEC
                          .listOf()
                          .fieldOf("villages")
                          .forGetter(VillageWorldData::stored),
                      ROAD_CODEC
                          .listOf()
                          .fieldOf("roads")
                          .forGetter(data -> List.copyOf(data.roads)))
                  .apply(i, VillageWorldData::new));

  public static final SavedDataType<VillageWorldData> TYPE =
      new SavedDataType<>(GuestSettlements.id("villages"), VillageWorldData::new, CODEC);

  final Map<Long, VillageNode> nodes = new HashMap<>();
  final Set<RoadEdge> roads = new HashSet<>();

  private VillageWorldData() {}

  private VillageWorldData(List<StoredNode> stored, List<RoadEdge> roads) {
    for (StoredNode node : stored) {
      nodes.put(node.id(), new VillageNode(node.id(), node.center(), node.state().orElse(null)));
    }
    this.roads.addAll(roads);
  }

  private List<StoredNode> stored() {
    List<StoredNode> result = new ArrayList<>();
    for (VillageNode node : nodes.values()) {
      result.add(new StoredNode(node.id(), node.center(), Optional.ofNullable(node.state())));
    }
    return result;
  }
}
