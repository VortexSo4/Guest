package com.vortexso.guest_atmosphere.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class FogLights {
  private static final int CHUNK_RADIUS = 4;
  private static final int SECTION_RADIUS = 3;
  private static final int MIN_EMISSION = 10;
  private static final int MAX_LIGHTS = 48;
  private static final int SEGMENTS = 10;

  private static final float DENSE_FOG = 96.0F;

  private static final int WARM = 0xFFC870;
  private static final float MAX_ALPHA = 0.55F;

  private static final List<BlockPos> FOUND = new ArrayList<>();
  private static List<BlockPos> lights = List.of();
  private static int scanIndex;

  private FogLights() {}

  public static void tick() {
    Minecraft minecraft = Minecraft.getInstance();
    ClientLevel level = minecraft.level;
    if (level == null
        || level.dimension() != Level.OVERWORLD
        || WeatherFog.fogEnd() > DENSE_FOG
        || minecraft.player == null) {
      lights = List.of();
      FOUND.clear();
      scanIndex = 0;
      return;
    }
    BlockPos camera = minecraft.gameRenderer.getMainCamera().blockPosition();
    int side = 2 * CHUNK_RADIUS + 1;
    int dx = scanIndex % side - CHUNK_RADIUS;
    int dz = scanIndex / side - CHUNK_RADIUS;
    scan(level, (camera.getX() >> 4) + dx, (camera.getZ() >> 4) + dz, camera);
    if (++scanIndex >= side * side) {
      scanIndex = 0;
      FOUND.sort(Comparator.comparingDouble(pos -> pos.distSqr(camera)));
      lights = List.copyOf(FOUND.subList(0, Math.min(MAX_LIGHTS, FOUND.size())));
      FOUND.clear();
    }
  }

  private static void scan(ClientLevel level, int chunkX, int chunkZ, BlockPos camera) {
    if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
      return;
    }
    LevelChunk chunk = level.getChunk(chunkX, chunkZ);
    int cameraSection = SectionPos.blockToSectionCoord(camera.getY());
    for (int sectionY = cameraSection - SECTION_RADIUS;
        sectionY <= cameraSection + SECTION_RADIUS;
        sectionY++) {
      int index = level.getSectionIndexFromSectionY(sectionY);
      if (index < 0 || index >= level.getSectionsCount()) {
        continue;
      }
      LevelChunkSection section = chunk.getSection(index);
      if (section.hasOnlyAir()
          || !section.maybeHas(state -> state.getLightEmission() >= MIN_EMISSION)) {
        continue;
      }
      for (int y = 0; y < 16; y++) {
        for (int z = 0; z < 16; z++) {
          for (int x = 0; x < 16; x++) {
            if (section.getBlockState(x, y, z).getLightEmission() >= MIN_EMISSION) {
              FOUND.add(
                  new BlockPos(
                      (chunkX << 4) + x,
                      SectionPos.sectionToBlockCoord(sectionY) + y,
                      (chunkZ << 4) + z));
            }
          }
        }
      }
    }
  }

  public static void render(LevelRenderState state, PoseStack poseStack) {
    if (lights.isEmpty()) {
      return;
    }
    Minecraft minecraft = Minecraft.getInstance();
    ClientLevel level = minecraft.level;
    if (level == null) {
      return;
    }

    int fog = WeatherFog.fogColor();
    float darkness =
        1.0F
            - Mth.clamp(
                (Math.max(ARGB.redFloat(fog), Math.max(ARGB.greenFloat(fog), ARGB.blueFloat(fog)))
                        - 0.25F)
                    / 0.5F,
                0.0F,
                0.85F);
    Vec3 camera = state.cameraRenderState.pos;
    Quaternionf facing = state.cameraRenderState.orientation;
    Vector3f right = facing.transform(new Vector3f(1.0F, 0.0F, 0.0F));
    Vector3f up = facing.transform(new Vector3f(0.0F, 1.0F, 0.0F));
    MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
    RenderType type = RenderTypes.debugQuads();
    VertexConsumer buffer = buffers.getBuffer(type);
    PoseStack.Pose pose = poseStack.last();
    for (BlockPos light : lights) {
      double x = light.getX() + 0.5 - camera.x;
      double y = light.getY() + 0.5 - camera.y;
      double z = light.getZ() + 0.5 - camera.z;
      double distance = Math.sqrt(x * x + y * y + z * z);
      float alpha = MAX_ALPHA * darkness * WeatherFog.fogAt(distance);
      if (alpha < 0.01F) {
        continue;
      }
      float radius = (float) (0.8 + 0.04 * distance);
      int center = ARGB.color(alpha, WARM);
      int rim = ARGB.color(0.0F, WARM);
      for (int i = 0; i < SEGMENTS; i++) {
        double a0 = Math.PI * 2.0 * i / SEGMENTS;
        double a1 = Math.PI * 2.0 * (i + 1) / SEGMENTS;
        float c0 = (float) Math.cos(a0) * radius;
        float s0 = (float) Math.sin(a0) * radius;
        float c1 = (float) Math.cos(a1) * radius;
        float s1 = (float) Math.sin(a1) * radius;

        buffer.addVertex(pose, (float) x, (float) y, (float) z).setColor(center);
        buffer
            .addVertex(
                pose,
                (float) x + right.x() * c0 + up.x() * s0,
                (float) y + right.y() * c0 + up.y() * s0,
                (float) z + right.z() * c0 + up.z() * s0)
            .setColor(rim);
        buffer
            .addVertex(
                pose,
                (float) x + right.x() * c1 + up.x() * s1,
                (float) y + right.y() * c1 + up.y() * s1,
                (float) z + right.z() * c1 + up.z() * s1)
            .setColor(rim);
        buffer.addVertex(pose, (float) x, (float) y, (float) z).setColor(center);
      }
    }
    buffers.endBatch(type);
  }
}
