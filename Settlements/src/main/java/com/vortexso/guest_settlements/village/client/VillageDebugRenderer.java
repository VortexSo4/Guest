package com.vortexso.guest_settlements.village.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.vortexso.guest_settlements.village.VillageDebugSnapshot;
import com.vortexso.guest_settlements.village.VillageWorldManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
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

    private VillageDebugRenderer() {
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent.AfterTranslucentBlocks event) {
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer server = minecraft.getSingleplayerServer();

        if (server == null || minecraft.level == null) {
            return;
        }

        ServerLevel serverLevel = server.getLevel(minecraft.level.dimension());
        if (serverLevel == null) {
            return;
        }

        VillageDebugSnapshot snapshot =
                VillageWorldManager.get(serverLevel).snapshot();

        PoseStack poseStack = event.getPoseStack();

        MultiBufferSource.BufferSource buffers =
                minecraft.renderBuffers().bufferSource();

        Vec3 camera =
                minecraft.gameRenderer.getMainCamera().position();

        VertexConsumer lines = buffers.getBuffer(RenderTypes.LINES);
        renderVillageLines(snapshot, poseStack, lines, camera);
        renderRoads(snapshot, poseStack, lines, camera);
        buffers.endBatch();

        renderVillageLabels(snapshot, poseStack, buffers, camera);
        buffers.endBatch();
    }

    private static void renderVillageLines(
            VillageDebugSnapshot snapshot,
            PoseStack poseStack,
            VertexConsumer lines,
            Vec3 camera
    ) {
        for (VillageDebugSnapshot.VillageSnapshot village : snapshot.villages()) {
            double distance = village.center().distToCenterSqr(camera);
            if (distance > MAX_DISTANCE_SQR) continue;

            if (village.structureBox() != null) {
                renderBox(poseStack, lines, village.structureBox(), camera, VILLAGE_COLOR);
            } else {
                renderPoint(
                        poseStack, lines,
                        village.center().getX() + 0.5,
                        village.center().getY() + 1.0,
                        village.center().getZ() + 0.5,
                        camera, VIRTUAL_COLOR
                );
            }

            for (VillageDebugSnapshot.FarmSnapshot farm : village.farms()) {
                if (farm.farmBox() == null) continue;
                renderBox(poseStack, lines, farm.farmBox(), camera, FARM_COLOR);
            }
        }
    }

    private static void renderVillageLabels(
            VillageDebugSnapshot snapshot,
            PoseStack poseStack,
            MultiBufferSource.BufferSource buffers,
            Vec3 camera
    ) {
        int villageCount = 0;
        int farmCount = 0;
        int labelCount = 0;

        for (VillageDebugSnapshot.VillageSnapshot village : snapshot.villages()) {
            villageCount++;

            double distance = village.center().distToCenterSqr(camera);
            if (distance > MAX_DISTANCE_SQR) {
                continue;
            }

            for (VillageDebugSnapshot.FarmSnapshot farm : village.farms()) {
                farmCount++;

                if (farm.farmBox() == null) {
                    continue;
                }

                BoundingBox box = farm.farmBox();
                Vec3 textPosition = new Vec3(
                        (box.minX() + box.maxX() + 1) * 0.5,
                        box.maxY() + 1.5,
                        (box.minZ() + box.maxZ() + 1) * 0.5
                );

                String text = "Farmland Amount: " + farm.farmlandAmount();
                if (!farm.complete()) text += " *";

                renderFloatingText(poseStack, buffers, text, textPosition, camera, 0xFFFFFFFF);
                labelCount++;
            }
        }
    }

    private static void renderRoads(
            VillageDebugSnapshot snapshot,
            PoseStack poseStack,
            VertexConsumer lines,
            Vec3 camera
    ) {
        for (VillageDebugSnapshot.RoadSnapshot road :
                snapshot.roads()) {

            if (road.firstCenter().distToCenterSqr(camera)
                    > MAX_DISTANCE_SQR
                    && road.secondCenter().distToCenterSqr(camera)
                    > MAX_DISTANCE_SQR) {
                continue;
            }

            double x1 =
                    road.firstCenter().getX()
                            + 0.5
                            - camera.x;

            double y1 =
                    road.firstCenter().getY()
                            + 1.0
                            - camera.y;

            double z1 =
                    road.firstCenter().getZ()
                            + 0.5
                            - camera.z;

            double x2 =
                    road.secondCenter().getX()
                            + 0.5
                            - camera.x;

            double y2 =
                    road.secondCenter().getY()
                            + 1.0
                            - camera.y;

            double z2 =
                    road.secondCenter().getZ()
                            + 0.5
                            - camera.z;

            addLine(
                    poseStack,
                    lines,
                    x1, y1, z1,
                    x2, y2, z2,
                    ROAD_COLOR
            );
        }
    }

    private static void renderBox(
            PoseStack poseStack,
            VertexConsumer consumer,
            BoundingBox box,
            Vec3 camera,
            int color
    ) {
        double minX = box.minX() - camera.x;
        double minY = box.minY() - camera.y;
        double minZ = box.minZ() - camera.z;

        double maxX = box.maxX() + 1.0 - camera.x;
        double maxY = box.maxY() + 1.0 - camera.y;
        double maxZ = box.maxZ() + 1.0 - camera.z;

        addLine(
                poseStack, consumer,
                minX, minY, minZ,
                maxX, minY, minZ,
                color
        );

        addLine(
                poseStack, consumer,
                maxX, minY, minZ,
                maxX, minY, maxZ,
                color
        );

        addLine(
                poseStack, consumer,
                maxX, minY, maxZ,
                minX, minY, maxZ,
                color
        );

        addLine(
                poseStack, consumer,
                minX, minY, maxZ,
                minX, minY, minZ,
                color
        );

        addLine(
                poseStack, consumer,
                minX, maxY, minZ,
                maxX, maxY, minZ,
                color
        );

        addLine(
                poseStack, consumer,
                maxX, maxY, minZ,
                maxX, maxY, maxZ,
                color
        );

        addLine(
                poseStack, consumer,
                maxX, maxY, maxZ,
                minX, maxY, maxZ,
                color
        );

        addLine(
                poseStack, consumer,
                minX, maxY, maxZ,
                minX, maxY, minZ,
                color
        );

        addLine(
                poseStack, consumer,
                minX, minY, minZ,
                minX, maxY, minZ,
                color
        );

        addLine(
                poseStack, consumer,
                maxX, minY, minZ,
                maxX, maxY, minZ,
                color
        );

        addLine(
                poseStack, consumer,
                maxX, minY, maxZ,
                maxX, maxY, maxZ,
                color
        );

        addLine(
                poseStack, consumer,
                minX, minY, maxZ,
                minX, maxY, maxZ,
                color
        );
    }

    private static void renderPoint(
            PoseStack poseStack,
            VertexConsumer consumer,
            double x,
            double y,
            double z,
            Vec3 camera,
            int color
    ) {
        double minX = x - 1.0 - camera.x;
        double minY = y - 1.0 - camera.y;
        double minZ = z - 1.0 - camera.z;

        double maxX = x + 1.0 - camera.x;
        double maxY = y + 1.0 - camera.y;
        double maxZ = z + 1.0 - camera.z;

        addLine(
                poseStack, consumer,
                minX, minY, minZ,
                maxX, maxY, maxZ,
                color
        );

        addLine(
                poseStack, consumer,
                minX, minY, maxZ,
                maxX, maxY, minZ,
                color
        );

        addLine(
                poseStack, consumer,
                minX, maxY, minZ,
                maxX, minY, maxZ,
                color
        );

        addLine(
                poseStack, consumer,
                minX, maxY, maxZ,
                maxX, minY, minZ,
                color
        );
    }

    private static void addLine(
            PoseStack poseStack,
            VertexConsumer consumer,
            double x1,
            double y1,
            double z1,
            double x2,
            double y2,
            double z2,
            int color
    ) {
        float red =
                ((color >>> 16) & 0xFF) / 255.0F;

        float green =
                ((color >>> 8) & 0xFF) / 255.0F;

        float blue =
                (color & 0xFF) / 255.0F;

        float alpha =
                ((color >>> 24) & 0xFF) / 255.0F;

        float normalX = (float) (x2 - x1);
        float normalY = (float) (y2 - y1);
        float normalZ = (float) (z2 - z1);

        float lineWidth = 2.0F;

        consumer.addVertex(
                poseStack.last().pose(),
                (float) x1,
                (float) y1,
                (float) z1
        ).setColor(
                red,
                green,
                blue,
                alpha
        ).setNormal(
                normalX,
                normalY,
                normalZ
        ).setLineWidth(
                lineWidth
        );

        consumer.addVertex(
                poseStack.last().pose(),
                (float) x2,
                (float) y2,
                (float) z2
        ).setColor(
                red,
                green,
                blue,
                alpha
        ).setNormal(
                normalX,
                normalY,
                normalZ
        ).setLineWidth(
                lineWidth
        );
    }

    private static void renderFloatingText(
            PoseStack poseStack,
            MultiBufferSource.BufferSource buffers,
            String text,
            Vec3 position,
            Vec3 camera,
            int color
    ) {
        Minecraft minecraft = Minecraft.getInstance();

        poseStack.pushPose();

        poseStack.translate(
                position.x - camera.x,
                position.y - camera.y,
                position.z - camera.z
        );

        poseStack.mulPose(
                minecraft.gameRenderer
                        .getMainCamera()
                        .rotation()
        );

        float scale = 0.025F;

        poseStack.scale(
                scale,
                -scale,
                scale
        );

        int width = minecraft.font.width(text);

        minecraft.font.drawInBatch(
                text,
                -width / 2.0F,
                0.0F,
                color,
                false,
                poseStack.last().pose(),
                buffers,
                Font.DisplayMode.NORMAL,
                0,
                0xF000F0
        );

        poseStack.popPose();
    }
}