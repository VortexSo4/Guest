package com.vortexso.guest_settlements.village;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.List;

public final class VillageWorldManager {
    private static final int NEIGHBOR_SEARCH_RADIUS = 4096;
    private static final int VILLAGE_MATCH_RADIUS = 256;

    private static final BlockPos[] NEIGHBOR_PROBES = {
            new BlockPos(160, 0, 0),
            new BlockPos(-160, 0, 0),
            new BlockPos(0, 0, 160),
            new BlockPos(0, 0, -160),
            new BlockPos(113, 0, 113),
            new BlockPos(113, 0, -113),
            new BlockPos(-113, 0, 113),
            new BlockPos(-113, 0, -113)
    };

    private static final Set<Identifier> VANILLA_VILLAGES = Set.of(
            Identifier.fromNamespaceAndPath("minecraft", "village_plains"),
            Identifier.fromNamespaceAndPath("minecraft", "village_desert"),
            Identifier.fromNamespaceAndPath("minecraft", "village_savanna"),
            Identifier.fromNamespaceAndPath("minecraft", "village_snowy"),
            Identifier.fromNamespaceAndPath("minecraft", "village_taiga")
    );

    private static final Map<ServerLevel, VillageWorldManager> INSTANCES =
            new WeakHashMap<>();

    private final ServerLevel level;
    private final Map<Long, VillageNode> nodes = new HashMap<>();
    private final Set<RoadEdge> roads = new HashSet<>();
    private final Map<Long, List<VillageFarmRegion>> regionsByChunk = new HashMap<>();
    private final Set<Long> queuedChunks = new HashSet<>();

    private VillageWorldManager(ServerLevel level) {
        this.level = level;
    }

    public static synchronized VillageWorldManager get(ServerLevel level) {
        return INSTANCES.computeIfAbsent(level, VillageWorldManager::new);
    }

    public void queueChunk(ChunkPos pos) {
        long key = pos.pack();
        if (!queuedChunks.add(key)) {
            return;
        }

        MinecraftServer server = level.getServer();
        server.execute(() -> {
            queuedChunks.remove(key);
            processChunk(pos);
        });
    }

    private void processChunk(ChunkPos chunkPos) {
        StructureManager structures = level.structureManager();
        List<StructureStart> starts = structures.startsForStructure(
                chunkPos,
                this::isVanillaVillageStructure
        );

        for (StructureStart start : starts) {
            registerVillage(start);
        }

        markRegionsInChunkDirty(chunkPos);
        refreshDirtyRegions();
    }

    private void registerVillage(StructureStart start) {
        if (start == null) {
            return;
        }

        BoundingBox structureBox = start.getBoundingBox();
        BlockPos center = structureBox.getCenter();

        VillageNode node = findMatchingNode(center);
        if (node == null) {
            node = new VillageNode(center.asLong(), center);
            nodes.put(node.id(), node);
        }

        node.markLoaded(center, structureBox);

        rebuildFarmRegions(node, start);
        connectNearestNeighbor(node);
    }

    private void rebuildFarmRegions(VillageNode node, StructureStart start) {
        for (VillageFarmRegion oldRegion : node.farmRegions()) {
            unindexRegion(oldRegion);
        }

        List<VillageFarmRegion> regions = new ArrayList<>();

        for (StructurePiece piece : start.getPieces()) {
            BoundingBox pieceBox = piece.getBoundingBox();

            VillageFarmRegion region = new VillageFarmRegion(
                    node.id(),
                    pieceBox
            );

            region.update(
                    VillageFarmScanner.scan(level, pieceBox)
            );

            if (region.farmlandAmount() > 0 || !region.complete()) {
                regions.add(region);
                indexRegion(region);
            }
        }

        node.replaceFarmRegions(regions);
    }

    private void connectNearestNeighbor(VillageNode node) {
        BlockPos nearest = findNearestOtherVillage(node.center());
        if (nearest == null) {
            return;
        }

        VillageNode neighbor = findMatchingNode(nearest);
        if (neighbor == null) {
            long id = nearest.asLong();
            neighbor = nodes.get(id);
            if (neighbor == null) {
                neighbor = new VillageNode(id, nearest);
                nodes.put(id, neighbor);
            }
        }

        roads.add(RoadEdge.of(node.id(), neighbor.id()));
    }

    private BlockPos findNearestOtherVillage(BlockPos center) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (BlockPos offset : NEIGHBOR_PROBES) {
            BlockPos query = center.offset(offset);
            BlockPos candidate = level.findNearestMapStructure(
                    net.minecraft.tags.StructureTags.VILLAGE,
                    query,
                    NEIGHBOR_SEARCH_RADIUS,
                    false
            );

            if (candidate == null) {
                continue;
            }

            double distance = candidate.distSqr(center);
            if (distance <= 128.0 * 128.0) {
                continue;
            }

            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }

        return best;
    }

    public void handleBlockChange(BlockPos pos) {
        List<VillageFarmRegion> regions =
                regionsByChunk.get(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));

        if (regions == null) {
            return;
        }

        for (VillageFarmRegion region : regions) {
            if (region.pieceBox().isInside(pos)) {
                region.markDirty();
            }
        }

        level.getServer().execute(this::refreshDirtyRegions);
    }

    private void markRegionsInChunkDirty(ChunkPos chunkPos) {
        List<VillageFarmRegion> regions = regionsByChunk.get(chunkPos.pack());
        if (regions == null) {
            return;
        }

        for (VillageFarmRegion region : regions) {
            region.markDirty();
        }
    }

    private void refreshDirtyRegions() {
        for (VillageNode node : nodes.values()) {
            if (!node.loaded()) {
                continue;
            }

            for (VillageFarmRegion region : node.farmRegions()) {
                if (!region.dirty()) {
                    continue;
                }

                region.update(
                        VillageFarmScanner.scan(
                                level,
                                region.pieceBox()
                        )
                );
            }
        }
    }

    private void indexRegion(VillageFarmRegion region) {
        region.pieceBox()
                .intersectingChunks()
                .forEach(chunk ->
                        regionsByChunk
                                .computeIfAbsent(chunk.pack(), ignored -> new ArrayList<>())
                                .add(region)
                );
    }

    private void unindexRegion(VillageFarmRegion region) {
        region.pieceBox()
                .intersectingChunks()
                .forEach(chunk -> {
                    List<VillageFarmRegion> regions = regionsByChunk.get(chunk.pack());
                    if (regions == null) {
                        return;
                    }

                    regions.remove(region);
                    if (regions.isEmpty()) {
                        regionsByChunk.remove(chunk.pack());
                    }
                });
    }

    private VillageNode findMatchingNode(BlockPos center) {
        double radius = (double) VILLAGE_MATCH_RADIUS * VILLAGE_MATCH_RADIUS;

        return nodes.values().stream()
                .filter(node -> node.center().distSqr(center) <= radius)
                .min(Comparator.comparingDouble(node -> node.center().distSqr(center)))
                .orElse(null);
    }

    private boolean isVanillaVillageStructure(Structure structure) {
        Identifier key =
                level.registryAccess()
                        .lookupOrThrow(Registries.STRUCTURE)
                        .getKey(structure);

        return key != null && VANILLA_VILLAGES.contains(key);
    }

    public synchronized VillageDebugSnapshot snapshot() {
        List<VillageDebugSnapshot.VillageSnapshot> villages = new ArrayList<>();

        for (VillageNode node : nodes.values()) {
            List<VillageDebugSnapshot.FarmSnapshot> farms = new ArrayList<>();

            for (VillageFarmRegion region : node.farmRegions()) {
                farms.add(new VillageDebugSnapshot.FarmSnapshot(
                        region.pieceBox(),
                        region.farmBox(),
                        region.farmlandAmount(),
                        region.complete()
                ));
            }

            villages.add(new VillageDebugSnapshot.VillageSnapshot(
                    node.id(),
                    node.center(),
                    node.loaded(),
                    node.structureBox(),
                    List.copyOf(farms)
            ));
        }

        List<VillageDebugSnapshot.RoadSnapshot> roadSnapshots = new ArrayList<>();
        for (RoadEdge road : roads) {
            VillageNode first = nodes.get(road.firstVillageId());
            VillageNode second = nodes.get(road.secondVillageId());
            if (first == null || second == null) {
                continue;
            }

            roadSnapshots.add(new VillageDebugSnapshot.RoadSnapshot(
                    road.firstVillageId(),
                    road.secondVillageId(),
                    first.center(),
                    second.center()
            ));
        }

        return new VillageDebugSnapshot(
                List.copyOf(villages),
                List.copyOf(roadSnapshots)
        );
    }
}
