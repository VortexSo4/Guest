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

    private static final int VIRTUAL_NEIGHBOR_HOPS = 1;

    private static final int[][] NEIGHBOR_DIRECTIONS = {
            { 1,  0},
            {-1,  0},
            { 0,  1},
            { 0, -1},
            { 1,  1},
            { 1, -1},
            {-1,  1},
            {-1, -1}
    };

    private static final int[] NEIGHBOR_PROBE_RADII = {
            256,
            512,
            1024
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

    private final Map<Long, List<VillageFarmRegion>> regionsByChunk =
            new HashMap<>();

    private final Set<Long> queuedChunks = new HashSet<>();

    private VillageWorldManager(ServerLevel level) {
        this.level = level;
    }

    public static synchronized VillageWorldManager get(ServerLevel level) {
        return INSTANCES.computeIfAbsent(
                level,
                VillageWorldManager::new
        );
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
        StructureManager structures =
                level.structureManager();

        List<StructureStart> starts =
                structures.startsForStructure(
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

        BoundingBox structureBox =
                start.getBoundingBox();

        BlockPos center =
                structureBox.getCenter();

        VillageNode node =
                findMatchingNode(center);

        if (node == null) {
            node = new VillageNode(
                    center.asLong(),
                    center
            );

            nodes.put(
                    node.id(),
                    node
            );
        }

        boolean newlyLoaded = !node.loaded();

        node.markLoaded(
                center,
                structureBox
        );

        rebuildFarmRegions(
                node,
                start
        );

        if (newlyLoaded) {
            connectNearestNeighbor(node);
        }
    }

    private void rebuildFarmRegions(
            VillageNode node,
            StructureStart start
    ) {
        for (VillageFarmRegion oldRegion :
                node.farmRegions()) {

            unindexRegion(oldRegion);
        }

        List<VillageFarmRegion> regions =
                new ArrayList<>();

        for (StructurePiece piece :
                start.getPieces()) {

            BoundingBox pieceBox =
                    piece.getBoundingBox();

            VillageFarmRegion region =
                    new VillageFarmRegion(
                            node.id(),
                            pieceBox
                    );

            region.update(
                    VillageFarmScanner.scan(
                            level,
                            pieceBox
                    )
            );

            if (region.farmlandAmount() > 0
                    || !region.complete()) {

                regions.add(region);
                indexRegion(region);
            }
        }

        node.replaceFarmRegions(regions);
    }

    private void connectNearestNeighbor(
            VillageNode node
    ) {
        connectNearestNeighbor(
                node,
                null,
                VIRTUAL_NEIGHBOR_HOPS
        );
    }

    private void connectNearestNeighbor(
            VillageNode node,
            VillageNode excluded,
            int remainingVirtualHops
    ) {
        BlockPos nearest =
                findNearestOtherVillage(
                        node.center(),
                        node,
                        excluded
                );

        if (nearest == null) {
            return;
        }

        /*
         * findNearestMapStructure() gives us the generated structure's
         * locate position. We only trust its X/Z here.
         *
         * The Y coordinate of a virtual village is only for visualization,
         * so keep it aligned with the village from which we discovered it.
         */
        BlockPos virtualCenter =
                nearest.atY(
                        node.center().getY()
                );

        VillageNode neighbor =
                getOrCreateNode(virtualCenter);

        roads.add(
                RoadEdge.of(
                        node.id(),
                        neighbor.id()
                )
        );

        /*
         * One bounded extra step allows:
         *
         *     loaded A ---- virtual B ---- virtual C
         *
         * but does not continue into D, E, F...
         */
        if (!neighbor.loaded()
                && remainingVirtualHops > 0) {

            connectNearestNeighbor(
                    neighbor,
                    node,
                    remainingVirtualHops - 1
            );
        }
    }

    private VillageNode getOrCreateNode(
            BlockPos center
    ) {
        VillageNode existing =
                findMatchingNode(center);

        if (existing != null) {
            return existing;
        }

        VillageNode node =
                new VillageNode(
                        center.asLong(),
                        center
                );

        nodes.put(
                node.id(),
                node
        );

        return node;
    }

    private BlockPos findNearestOtherVillage(
            BlockPos center,
            VillageNode node,
            VillageNode excluded
    ) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int radius : NEIGHBOR_PROBE_RADII) {
            for (int[] direction :
                    NEIGHBOR_DIRECTIONS) {

                BlockPos query =
                        new BlockPos(
                                center.getX()
                                        + direction[0] * radius,
                                center.getY(),
                                center.getZ()
                                        + direction[1] * radius
                        );

                BlockPos candidate =
                        level.findNearestMapStructure(
                                net.minecraft.tags.StructureTags.VILLAGE,
                                query,
                                NEIGHBOR_SEARCH_RADIUS,
                                false
                        );

                if (candidate == null) {
                    continue;
                }

                /*
                 * The search is allowed to return the village we started
                 * from. Reject known copies of that village explicitly
                 * rather than relying on an arbitrary distance threshold.
                 */
                VillageNode matched =
                        findMatchingNode(candidate);

                if (matched == node
                        || matched == excluded) {

                    continue;
                }

                double distance =
                        horizontalDistanceSqr(
                                candidate,
                                center
                        );

                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
        }

        return best;
    }

    public void handleBlockChange(BlockPos pos) {
        List<VillageFarmRegion> regions =
                regionsByChunk.get(
                        ChunkPos.pack(
                                pos.getX() >> 4,
                                pos.getZ() >> 4
                        )
                );

        if (regions == null) {
            return;
        }

        for (VillageFarmRegion region : regions) {
            if (region.pieceBox().isInside(pos)) {
                region.markDirty();
            }
        }

        level.getServer().execute(
                this::refreshDirtyRegions
        );
    }

    private void markRegionsInChunkDirty(
            ChunkPos chunkPos
    ) {
        List<VillageFarmRegion> regions =
                regionsByChunk.get(
                        chunkPos.pack()
                );

        if (regions == null) {
            return;
        }

        for (VillageFarmRegion region : regions) {
            region.markDirty();
        }
    }

    private void refreshDirtyRegions() {
        for (VillageNode node :
                nodes.values()) {

            if (!node.loaded()) {
                continue;
            }

            for (VillageFarmRegion region :
                    node.farmRegions()) {

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

    private void indexRegion(
            VillageFarmRegion region
    ) {
        region.pieceBox()
                .intersectingChunks()
                .forEach(chunk ->
                        regionsByChunk
                                .computeIfAbsent(
                                        chunk.pack(),
                                        ignored -> new ArrayList<>()
                                )
                                .add(region)
                );
    }

    private void unindexRegion(
            VillageFarmRegion region
    ) {
        region.pieceBox()
                .intersectingChunks()
                .forEach(chunk -> {
                    List<VillageFarmRegion> regions =
                            regionsByChunk.get(
                                    chunk.pack()
                            );

                    if (regions == null) {
                        return;
                    }

                    regions.remove(region);

                    if (regions.isEmpty()) {
                        regionsByChunk.remove(
                                chunk.pack()
                        );
                    }
                });
    }

    private VillageNode findMatchingNode(
            BlockPos center
    ) {
        double radius =
                (double) VILLAGE_MATCH_RADIUS
                        * VILLAGE_MATCH_RADIUS;

        return nodes.values().stream()
                .filter(node ->
                        horizontalDistanceSqr(
                                node.center(),
                                center
                        ) <= radius
                )
                .min(
                        Comparator.comparingDouble(
                                node ->
                                        horizontalDistanceSqr(
                                                node.center(),
                                                center
                                        )
                        )
                )
                .orElse(null);
    }

    private static double horizontalDistanceSqr(
            BlockPos first,
            BlockPos second
    ) {
        double dx =
                first.getX()
                        - second.getX();

        double dz =
                first.getZ()
                        - second.getZ();

        return dx * dx + dz * dz;
    }

    private boolean isVanillaVillageStructure(
            Structure structure
    ) {
        Identifier key =
                level.registryAccess()
                        .lookupOrThrow(
                                Registries.STRUCTURE
                        )
                        .getKey(structure);

        return key != null
                && VANILLA_VILLAGES.contains(key);
    }

    public synchronized VillageDebugSnapshot snapshot() {
        List<VillageDebugSnapshot.VillageSnapshot>
                villages = new ArrayList<>();

        for (VillageNode node :
                nodes.values()) {

            List<VillageDebugSnapshot.FarmSnapshot>
                    farms = new ArrayList<>();

            for (VillageFarmRegion region :
                    node.farmRegions()) {

                farms.add(
                        new VillageDebugSnapshot.FarmSnapshot(
                                region.pieceBox(),
                                region.farmBox(),
                                region.farmlandAmount(),
                                region.complete()
                        )
                );
            }

            villages.add(
                    new VillageDebugSnapshot.VillageSnapshot(
                            node.id(),
                            node.center(),
                            node.loaded(),
                            node.structureBox(),
                            List.copyOf(farms)
                    )
            );
        }

        List<VillageDebugSnapshot.RoadSnapshot>
                roadSnapshots = new ArrayList<>();

        for (RoadEdge road : roads) {
            VillageNode first =
                    nodes.get(
                            road.firstVillageId()
                    );

            VillageNode second =
                    nodes.get(
                            road.secondVillageId()
                    );

            if (first == null
                    || second == null) {

                continue;
            }

            roadSnapshots.add(
                    new VillageDebugSnapshot.RoadSnapshot(
                            road.firstVillageId(),
                            road.secondVillageId(),
                            first.center(),
                            second.center()
                    )
            );
        }

        return new VillageDebugSnapshot(
                List.copyOf(villages),
                List.copyOf(roadSnapshots)
        );
    }
}