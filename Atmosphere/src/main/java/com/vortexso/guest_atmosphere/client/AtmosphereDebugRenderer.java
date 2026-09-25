package com.vortexso.guest_atmosphere.client;

import com.vortexso.guest_atmosphere.GuestAtmosphere;
import com.vortexso.guest_atmosphere.debug.AtmosphereDebug;
import com.vortexso.guest_atmosphere.trace.ChunkTraces;
import com.vortexso.guest_atmosphere.weather.WeatherModel.Sample;
import com.vortexso.guest_core.client.GuestGizmos;
import com.vortexso.guest_core.debug.GuestDebug;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Draws the {@code atmosphere} debug channel from the server-side snapshot (singleplayer). */
@EventBusSubscriber(modid = GuestAtmosphere.MODID, value = Dist.CLIENT)
public final class AtmosphereDebugRenderer {
  private static final int BORDER_COLOR = 0xFF55FFFF;
  private static final int BLEND_COLOR = 0x8855FFFF;
  private static final int TEXT_COLOR = 0xFFFFFFFF;
  private static final double TRACE_DISTANCE_SQR = 32.0 * 32.0;
  private static final double LINE_HEIGHT = 0.3;

  private AtmosphereDebugRenderer() {}

  @SubscribeEvent
  public static void render(RenderLevelStageEvent.AfterTranslucentBlocks event) {
    AtmosphereDebug.Snapshot snapshot = AtmosphereDebug.latest();
    Minecraft minecraft = Minecraft.getInstance();
    if (snapshot == null
        || minecraft.level == null
        || minecraft.player == null
        || !GuestDebug.isEnabled(GuestAtmosphere.DEBUG_CHANNEL)) {
      return;
    }
    Vec3 camera = minecraft.gameRenderer.getMainCamera().position();
    try (var ignored = minecraft.levelRenderer.collectPerFrameGizmos()) {
      renderBorders(snapshot, camera);
      for (AtmosphereDebug.Cell cell : snapshot.cells()) {
        Vec3 label = cell.labelPos().getCenter().add(0.0, 3.0, 0.0);
        lines(
            label,
            Component.translatable("guest_atmosphere.debug.cell", cell.x(), cell.z()),
            Component.translatable(
                "guest_atmosphere.debug.climate",
                Component.translatable(
                    "guest_atmosphere.climate." + cell.climate().name().toLowerCase(Locale.ROOT))),
            describe(cell.sample()),
            values(cell.sample()));
      }
      lines(
          camera.add(minecraft.player.getLookAngle().scale(4.0)).add(0.0, 1.2, 0.0),
          Component.translatable("guest_atmosphere.debug.here"),
          describe(snapshot.here()),
          values(snapshot.here()));
      renderTraces(snapshot, camera);
    }
  }

  private static void renderBorders(AtmosphereDebug.Snapshot snapshot, Vec3 camera) {
    int size = snapshot.cellSize();
    long cellX = (long) Math.floor((camera.x - snapshot.drift()) / size);
    long cellZ = (long) Math.floor(camera.z / size);
    double y = camera.y - 1.0;
    double band = snapshot.blendFraction() * size / 2.0;
    for (long i = cellX - 1; i <= cellX + 2; i++) {
      double x = i * size + snapshot.drift();
      GuestGizmos.line(
          new Vec3(x, y, camera.z - size), new Vec3(x, y, camera.z + size), BORDER_COLOR);
      for (double offset : new double[] {-band, band}) {
        GuestGizmos.line(
            new Vec3(x + offset, y, camera.z - size),
            new Vec3(x + offset, y, camera.z + size),
            BLEND_COLOR,
            1.0F);
      }
    }
    for (long j = cellZ - 1; j <= cellZ + 2; j++) {
      double z = j * size;
      GuestGizmos.line(
          new Vec3(camera.x - size, y, z), new Vec3(camera.x + size, y, z), BORDER_COLOR);
      for (double offset : new double[] {-band, band}) {
        GuestGizmos.line(
            new Vec3(camera.x - size, y, z + offset),
            new Vec3(camera.x + size, y, z + offset),
            BLEND_COLOR,
            1.0F);
      }
    }
  }

  private static void renderTraces(AtmosphereDebug.Snapshot snapshot, Vec3 camera) {
    for (AtmosphereDebug.Mark mark : snapshot.traces()) {
      if (mark.pos().distToCenterSqr(camera) <= TRACE_DISTANCE_SQR) {
        GuestGizmos.box(new AABB(mark.pos()).deflate(0.2), mark.kind().debugColor());
      }
    }
    for (AtmosphereDebug.ChunkCount chunk : snapshot.chunks()) {
      StringBuilder counts = new StringBuilder();
      for (ChunkTraces.Kind kind : ChunkTraces.Kind.values()) {
        int count = chunk.byKind()[kind.ordinal()];
        if (count > 0) {
          counts.append(' ').append(kind.displayName().getString()).append('=').append(count);
        }
      }
      Vec3 position =
          new Vec3(
              chunk.pos().getMiddleBlockX() + 0.5,
              camera.y + 2.0,
              chunk.pos().getMiddleBlockZ() + 0.5);
      lines(
          position,
          Component.translatable("guest_atmosphere.debug.traces", counts.toString().trim()),
          Component.translatable("guest_atmosphere.debug.since_update", chunk.sinceUpdate()));
    }
  }

  private static Component describe(Sample sample) {
    return Component.translatable(
        sample.state().aurora()
            ? "guest_atmosphere.debug.weather_aurora"
            : "guest_atmosphere.debug.weather",
        sample.state().type().displayName());
  }

  private static Component values(Sample sample) {
    return Component.translatable(
        "guest_atmosphere.debug.values",
        format(sample.state().intensity()),
        format(sample.state().wind()),
        format(sample.temperature()));
  }

  private static void lines(Vec3 top, Component... lines) {
    for (int i = 0; i < lines.length; i++) {
      GuestGizmos.text(lines[i], top.add(0.0, -i * LINE_HEIGHT, 0.0), TEXT_COLOR);
    }
  }

  private static String format(float value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }
}
