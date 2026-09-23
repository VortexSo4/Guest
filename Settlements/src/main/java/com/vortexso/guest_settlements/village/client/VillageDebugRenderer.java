package com.vortexso.guest_settlements.village.client;

import com.vortexso.guest_core.client.GuestGizmos;
import com.vortexso.guest_settlements.village.VillageDebugSnapshot;
import com.vortexso.guest_settlements.village.VillageWorldManager;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

@EventBusSubscriber(
        modid = "guest_settlements",
        value = Dist.CLIENT
)
public final class VillageDebugRenderer {
    private static final double MAX_DISTANCE_SQR = 512.0 * 512.0;
    private static final int VILLAGE_COLOR = 0xFF55FFFF;
    private static final int FARM_COLOR = 0xFF55FF55;
    private static final int ROAD_COLOR = 0xFFFFFF55;
    private static final int VIRTUAL_COLOR = 0xFFFFAA55;
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    private VillageDebugRenderer() {}

    @SubscribeEvent
    public static void render(RenderLevelStageEvent.AfterTranslucentBlocks event) {
        Minecraft minecraft =
                Minecraft.getInstance();

        MinecraftServer server =
                minecraft.getSingleplayerServer();

        if (server == null || minecraft.level == null) {
            return;
        }

        ServerLevel level =
                server.getLevel(
                        minecraft.level.dimension()
                );

        if (level == null) {
            return;
        }

        VillageDebugSnapshot snapshot =
                VillageWorldManager
                        .get(level)
                        .snapshot();

        Vec3 camera =
                minecraft.gameRenderer
                        .getMainCamera()
                        .position();

        /*
         * LevelRenderer owns the per-frame gizmo collector.
         * The collection is automatically released after this block.
         */
        try (var ignored =
                     minecraft.levelRenderer
                             .collectPerFrameGizmos()) {

            renderVillages(
                    snapshot,
                    camera
            );

            renderRoads(
                    snapshot,
                    camera
            );

            renderFarmLabels(
                    snapshot,
                    camera
            );
        }
    }

    private static void renderVillages(
            VillageDebugSnapshot snapshot,
            Vec3 camera
    ) {
        for (VillageDebugSnapshot.VillageSnapshot village :
                snapshot.villages()) {

            if (!isNear(
                    village.center(),
                    camera
            )) {
                continue;
            }

            if (village.structureBox() != null) {
                GuestGizmos.box(
                        village.structureBox(),
                        VILLAGE_COLOR
                );
            } else {
                GuestGizmos.point(
                        village.center()
                                .getCenter(),
                        VIRTUAL_COLOR
                );
            }

            for (VillageDebugSnapshot.FarmSnapshot farm :
                    village.farms()) {

                BoundingBox farmBox =
                        farm.farmBox();

                if (farmBox == null) {
                    continue;
                }

                GuestGizmos.box(
                        farmBox,
                        FARM_COLOR
                );
            }
        }
    }

    private static void renderRoads(
            VillageDebugSnapshot snapshot,
            Vec3 camera
    ) {
        for (VillageDebugSnapshot.RoadSnapshot road :
                snapshot.roads()) {

            if (!isRoadNear(
                    road,
                    camera
            )) {
                continue;
            }

            Vec3 first =
                    road.firstCenter()
                            .getCenter();

            Vec3 second =
                    road.secondCenter()
                            .getCenter();

            GuestGizmos.line(
                    first.add(0.0, 0.5, 0.0),
                    second.add(0.0, 0.5, 0.0),
                    ROAD_COLOR
            );
        }
    }

    private static void renderFarmLabels(
            VillageDebugSnapshot snapshot,
            Vec3 camera
    ) {
        for (VillageDebugSnapshot.VillageSnapshot village :
                snapshot.villages()) {

            if (!isNear(
                    village.center(),
                    camera
            )) {
                continue;
            }

            for (VillageDebugSnapshot.FarmSnapshot farm :
                    village.farms()) {

                BoundingBox box =
                        farm.farmBox();

                if (box == null) {
                    continue;
                }

                Vec3 position =
                        new Vec3(
                                (box.minX()
                                        + box.maxX()
                                        + 1) * 0.5,
                                box.maxY() + 1.5,
                                (box.minZ()
                                        + box.maxZ()
                                        + 1) * 0.5
                        );

                String text =
                        "Farmland Amount: "
                                + farm.farmlandAmount();

                if (!farm.complete()) {
                    text += " *";
                }

                GuestGizmos.text(
                        text,
                        position,
                        TEXT_COLOR
                );
            }
        }
    }

    private static boolean isNear(
            net.minecraft.core.BlockPos position,
            Vec3 camera
    ) {
        return position.distToCenterSqr(camera)
                <= MAX_DISTANCE_SQR;
    }

    private static boolean isRoadNear(
            VillageDebugSnapshot.RoadSnapshot road,
            Vec3 camera
    ) {
        return road.firstCenter()
                .distToCenterSqr(camera)
                <= MAX_DISTANCE_SQR
                || road.secondCenter()
                .distToCenterSqr(camera)
                <= MAX_DISTANCE_SQR;
    }
}