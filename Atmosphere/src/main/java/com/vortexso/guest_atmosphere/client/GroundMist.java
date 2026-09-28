package com.vortexso.guest_atmosphere.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.vortexso.guest_atmosphere.AtmosphereConfig;
import com.vortexso.guest_atmosphere.GuestAtmosphere;
import com.vortexso.guest_atmosphere.network.WeatherSyncPayload;
import com.vortexso.guest_atmosphere.weather.WeatherModel;
import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.world.WeatherState;
import com.vortexso.guest_core.api.world.WeatherType;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.Tags;

@EventBusSubscriber(modid = GuestAtmosphere.MODID, value = Dist.CLIENT)
public final class GroundMist {
  private static final int CELL = 4;
  private static final int CELLS_PER_CHUNK = 16 / CELL;

  private static final int MAX_CELLS = 2_500;

  private static final int BUILDS_PER_TICK = 12;

  private static final long REFRESH_TICKS = 1_200L;

  private static final float MAX_ALPHA = 0.42F;

  private static final double PATCH_CELLS = 3.0;

  private static final long SALT = 0x6C8E9CF570932BD5L;

  private static final byte DRY = 0;
  private static final byte SWAMP = 1;
  private static final byte WATER = 2;

  private record MistChunk(byte[] kinds, short[] surfaces, long built) {}

  private static final Long2ObjectOpenHashMap<MistChunk> CHUNKS = new Long2ObjectOpenHashMap<>();
  private static ClientLevel cachedLevel;

  private static float[] density = new float[0];

  private static short[] surface = new short[0];
  private static int gridSize;
  private static int gridMinX;
  private static int gridMinZ;
  private static int drawnCells;

  private static double driftX;
  private static double driftZ;

  private GroundMist() {}

  public static int drawnCells() {
    return drawnCells;
  }

  @SubscribeEvent
  public static void onClientTick(ClientTickEvent.Post event) {
    Minecraft minecraft = Minecraft.getInstance();
    ClientLevel level = minecraft.level;
    if (level != cachedLevel) {
      CHUNKS.clear();
      cachedLevel = level;
    }
    if (level == null
        || minecraft.isPaused()
        || !AtmosphereConfig.GROUND_MIST.get()
        || level.dimension() != Level.OVERWORLD) {
      gridSize = 0;
      return;
    }
    WeatherSyncPayload weather = WeatherSyncPayload.latest();
    WeatherState state = ClientWeatherEffects.state();

    double drift = 0.003 + 0.012 * state.wind();
    driftX += Math.cos(weather.windAngle()) * drift;
    driftZ += Math.sin(weather.windAngle()) * drift;

    long tickOfDay = GuestTime.tickOfDay(GuestTime.gameTime(level));
    double dawn = WeatherModel.dawn(tickOfDay);
    double night = GuestTime.isNight(GuestTime.gameTime(level)) ? 1.0 : 0.0;
    double air = weatherFactor(state);

    float swamp = (float) ((0.5 + 0.35 * Math.max(dawn, night)) * air);
    float water = (float) (0.8 * dawn * air);

    Vec3 camera = minecraft.gameRenderer.getMainCamera().position();
    int radiusCells = AtmosphereConfig.GROUND_MIST_DISTANCE.get() / CELL + 1;
    gridSize = 2 * radiusCells + 1;
    gridMinX = Mth.floor(camera.x / CELL) - radiusCells;
    gridMinZ = Mth.floor(camera.z / CELL) - radiusCells;
    if (density.length < gridSize * gridSize) {
      density = new float[gridSize * gridSize];
      surface = new short[gridSize * gridSize];
    }
    if (swamp <= 0.01F && water <= 0.01F) {
      Arrays.fill(density, 0.0F);
      return;
    }

    long now = level.getGameTime();
    int builds = BUILDS_PER_TICK;
    MistChunk chunk = null;
    long chunkKey = Long.MIN_VALUE;
    for (int i = 0; i < gridSize; i++) {
      for (int j = 0; j < gridSize; j++) {
        int cellX = gridMinX + i;
        int cellZ = gridMinZ + j;
        int index = i * gridSize + j;
        density[index] = 0.0F;
        long key =
            ChunkPos.pack(
                Math.floorDiv(cellX, CELLS_PER_CHUNK), Math.floorDiv(cellZ, CELLS_PER_CHUNK));
        if (key != chunkKey) {
          chunkKey = key;
          chunk = CHUNKS.get(key);
          if ((chunk == null || now - chunk.built() > REFRESH_TICKS) && builds > 0) {
            MistChunk built = build(level, ChunkPos.getX(key), ChunkPos.getZ(key), now);
            if (built != null) {
              CHUNKS.put(key, built);
              chunk = built;
              builds--;
            }
          }
        }
        if (chunk == null) {
          continue;
        }
        int local =
            Math.floorMod(cellX, CELLS_PER_CHUNK) * CELLS_PER_CHUNK
                + Math.floorMod(cellZ, CELLS_PER_CHUNK);
        byte kind = chunk.kinds()[local];
        float potential = kind == SWAMP ? swamp : kind == WATER ? water : 0.0F;
        if (potential <= 0.01F) {
          continue;
        }
        double n = noise((cellX - driftX) / PATCH_CELLS, (cellZ - driftZ) / PATCH_CELLS);
        density[index] = (float) Mth.clamp((n - (1.0 - potential)) / 0.3, 0.0, 1.0);
        surface[index] = chunk.surfaces()[local];
      }
    }

    if (now % 100 == 0) {
      int chunkRadius = radiusCells / CELLS_PER_CHUNK + 4;
      int centerX = Mth.floor(camera.x) >> 4;
      int centerZ = Mth.floor(camera.z) >> 4;
      CHUNKS
          .long2ObjectEntrySet()
          .removeIf(
              entry ->
                  Math.abs(ChunkPos.getX(entry.getLongKey()) - centerX) > chunkRadius
                      || Math.abs(ChunkPos.getZ(entry.getLongKey()) - centerZ) > chunkRadius);
    }
  }

  @SubscribeEvent
  public static void onRender(RenderLevelStageEvent.AfterTranslucentBlocks event) {
    drawnCells = 0;
    if (gridSize == 0) {
      return;
    }
    Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
    float range = AtmosphereConfig.GROUND_MIST_DISTANCE.get();
    int fog = WeatherFog.fogColor();
    float brightness =
        Math.max(ARGB.redFloat(fog), Math.max(ARGB.greenFloat(fog), ARGB.blueFloat(fog)));

    float light = 0.3F + 0.7F * brightness;
    int top =
        ARGB.srgbLerp(
            0.6F, fog, ARGB.colorFromFloat(1.0F, light, light, Math.min(1.0F, light * 1.05F)));
    int side = ARGB.scaleRGB(top, 0.9F);

    MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
    RenderType type = RenderTypes.debugFilledBox();
    VertexConsumer buffer = buffers.getBuffer(type);
    PoseStack.Pose pose = event.getPoseStack().last();
    int budget = MAX_CELLS;

    int center = gridSize / 2;
    rings:
    for (int ring = 0; ring <= center; ring++) {
      for (int i = center - ring; i <= center + ring; i++) {
        for (int j = center - ring; j <= center + ring; j++) {
          if (Math.max(Math.abs(i - center), Math.abs(j - center)) != ring) {
            continue;
          }
          float d = density[i * gridSize + j];
          if (d <= 0.01F) {
            continue;
          }
          double x0 = (gridMinX + i) * (double) CELL;
          double z0 = (gridMinZ + j) * (double) CELL;
          double distance = Math.hypot(x0 + CELL / 2.0 - camera.x, z0 + CELL / 2.0 - camera.z);
          float fade =
              (1.0F - smoothstep((float) ((distance - range * 0.7) / (range * 0.3))))
                  * (1.0F - WeatherFog.fogAt(distance));
          float alpha = MAX_ALPHA * d * fade;
          if (alpha < 0.01F) {
            continue;
          }
          int base = surface[i * gridSize + j];
          float y0 = base + 0.15F;
          float y1 = top(base, d);
          cell(buffer, pose, camera, i, j, x0, z0, y0, y1, alpha, top, side);
          drawnCells++;
          if (--budget <= 0) {
            break rings;
          }
        }
      }
    }
    buffers.endBatch(type);
  }

  private static void cell(
      VertexConsumer buffer,
      PoseStack.Pose pose,
      Vec3 camera,
      int i,
      int j,
      double worldX,
      double worldZ,
      float y0,
      float y1,
      float alpha,
      int top,
      int side) {
    float x0 = (float) (worldX - camera.x);
    float z0 = (float) (worldZ - camera.z);
    float x1 = x0 + CELL;
    float z1 = z0 + CELL;
    float b = (float) (y0 - camera.y);
    float t = (float) (y1 - camera.y);
    int topColor = ARGB.color(alpha, top);
    int sideColor = ARGB.color(alpha, side);
    quad(buffer, pose, x0, t, z0, x0, t, z1, x1, t, z1, x1, t, z0, topColor);
    if (camera.y < y0) {
      quad(buffer, pose, x0, b, z0, x1, b, z0, x1, b, z1, x0, b, z1, sideColor);
    }
    float east = sideFrom(i + 1, j, y0, y1) - (float) camera.y;
    if (east < t) {
      quad(buffer, pose, x1, east, z0, x1, t, z0, x1, t, z1, x1, east, z1, sideColor);
    }
    float west = sideFrom(i - 1, j, y0, y1) - (float) camera.y;
    if (west < t) {
      quad(buffer, pose, x0, west, z0, x0, west, z1, x0, t, z1, x0, t, z0, sideColor);
    }
    float north = sideFrom(i, j - 1, y0, y1) - (float) camera.y;
    if (north < t) {
      quad(buffer, pose, x0, north, z0, x0, t, z0, x1, t, z0, x1, north, z0, sideColor);
    }
    float south = sideFrom(i, j + 1, y0, y1) - (float) camera.y;
    if (south < t) {
      quad(buffer, pose, x0, south, z1, x1, south, z1, x1, t, z1, x0, t, z1, sideColor);
    }
  }

  private static float sideFrom(int i, int j, float y0, float y1) {
    if (i < 0 || j < 0 || i >= gridSize || j >= gridSize) {
      return y0;
    }
    float d = density[i * gridSize + j];
    if (d <= 0.01F) {
      return y0;
    }
    return Math.max(y0, Math.min(y1, top(surface[i * gridSize + j], d)));
  }

  private static float top(int surface, float density) {
    return surface + 0.55F + 0.5F * density;
  }

  private static void quad(
      VertexConsumer buffer,
      PoseStack.Pose pose,
      float ax,
      float ay,
      float az,
      float bx,
      float by,
      float bz,
      float cx,
      float cy,
      float cz,
      float dx,
      float dy,
      float dz,
      int color) {
    buffer.addVertex(pose, ax, ay, az).setColor(color);
    buffer.addVertex(pose, bx, by, bz).setColor(color);
    buffer.addVertex(pose, cx, cy, cz).setColor(color);
    buffer.addVertex(pose, dx, dy, dz).setColor(color);
  }

  private static double weatherFactor(WeatherState state) {
    double calm = 1.0 - Mth.clamp((state.wind() - 0.4) / 0.35, 0.0, 1.0);
    WeatherType type = state.type();
    double factor =
        switch (type) {
          case SNOWSTORM, BLIZZARD, SANDSTORM, THUNDERSTORM -> 0.0;
          case HEAT -> 0.3;
          case DRIZZLE, RAIN, DOWNPOUR, SNOWFALL -> 0.7;
          case FOG -> 1.0 + 0.3 * state.intensity();
          default -> 1.0;
        };
    return calm * factor;
  }

  private static MistChunk build(ClientLevel level, int chunkX, int chunkZ, long now) {
    if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
      return null;
    }
    byte[] kinds = new byte[CELLS_PER_CHUNK * CELLS_PER_CHUNK];
    short[] surfaces = new short[kinds.length];
    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    for (int a = 0; a < CELLS_PER_CHUNK; a++) {
      for (int b = 0; b < CELLS_PER_CHUNK; b++) {
        int x = (chunkX << 4) + a * CELL + CELL / 2;
        int z = (chunkZ << 4) + b * CELL + CELL / 2;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        Holder<Biome> biome = level.getBiome(pos.set(x, y, z));
        boolean water = level.getFluidState(pos.set(x, y - 1, z)).is(FluidTags.WATER);
        int index = a * CELLS_PER_CHUNK + b;
        surfaces[index] = (short) y;
        if (biome.is(Tags.Biomes.IS_SWAMP)) {
          kinds[index] = SWAMP;
        } else if (water
            && !biome.is(BiomeTags.IS_OCEAN)
            && biome.value().warmEnoughToRain(pos, level.getSeaLevel())) {
          kinds[index] = WATER;
        } else {
          kinds[index] = DRY;
        }
      }
    }
    return new MistChunk(kinds, surfaces, now);
  }

  private static double noise(double x, double z) {
    return 0.65 * valueNoise(x, z, 0L) + 0.35 * valueNoise(x * 2.3 + 17.1, z * 2.3 - 5.7, 1L);
  }

  private static double valueNoise(double x, double z, long octave) {
    long x0 = (long) Math.floor(x);
    long z0 = (long) Math.floor(z);
    double fx = x - x0;
    double fz = z - z0;
    double sx = fx * fx * (3.0 - 2.0 * fx);
    double sz = fz * fz * (3.0 - 2.0 * fz);
    double a = lattice(x0, z0, octave);
    double b = lattice(x0 + 1, z0, octave);
    double c = lattice(x0, z0 + 1, octave);
    double d = lattice(x0 + 1, z0 + 1, octave);
    return Mth.lerp(sz, Mth.lerp(sx, a, b), Mth.lerp(sx, c, d));
  }

  private static double lattice(long x, long z, long octave) {
    return GuestHash.unit(GuestHash.hash(SALT + octave, x, z));
  }

  private static float smoothstep(float t) {
    float c = Mth.clamp(t, 0.0F, 1.0F);
    return c * c * (3.0F - 2.0F * c);
  }
}
