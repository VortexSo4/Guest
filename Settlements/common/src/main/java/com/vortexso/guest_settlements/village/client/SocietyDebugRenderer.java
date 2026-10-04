package com.vortexso.guest_settlements.village.client;

import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.client.GuestGizmos;
import com.vortexso.guest_core.debug.GuestDebug;
import com.vortexso.guest_settlements.GuestSettlements;
import com.vortexso.guest_settlements.SettlementsConfig;
import com.vortexso.guest_settlements.illager.IllagerCamps;
import com.vortexso.guest_settlements.illager.IllagerRespect;
import com.vortexso.guest_settlements.society.SocietyData;
import com.vortexso.guest_settlements.village.Emigration;
import com.vortexso.guest_settlements.village.VillageDebugSnapshot;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class SocietyDebugRenderer {
  private static final double MAX_DISTANCE_SQR = 512.0 * 512.0;
  private static final double LABEL_DISTANCE_SQR = 64.0 * 64.0;
  private static final int ROAD_COLOR = 0xFFFFFF55;
  private static final int ROAD_EDGE_COLOR = 0xFFC8A040;
  private static final int ABANDONED_COLOR = 0xFF808080;
  private static final int UNPLANNED_COLOR = 0x80FFFF55;
  private static final int CAMP_COLOR = 0xFFAA2222;
  private static final int PEN_COLOR = 0xFFAA7744;
  private static final int TRACE_COLOR = 0xFF9966FF;
  private static final int TEXT_COLOR = 0xFFFFFFFF;
  private static final int EMIGRANT_COLOR = 0xFF55FF99;
  private static final double LINE_HEIGHT = 0.3;

  private SocietyDebugRenderer() {}

  static void renderRoad(VillageDebugSnapshot.RoadSnapshot road, Vec3 camera) {
    List<BlockPos> points = road.road();
    if (points.size() < 2) {
      if (near(road.firstCenter(), camera) || near(road.secondCenter(), camera)) {
        GuestGizmos.line(
            road.firstCenter().getCenter().add(0.0, 0.5, 0.0),
            road.secondCenter().getCenter().add(0.0, 0.5, 0.0),
            UNPLANNED_COLOR);
      }
      return;
    }
    double half = SettlementsConfig.value(SettlementsConfig.ROAD_WIDTH) / 2.0;
    int color = road.abandoned() ? ABANDONED_COLOR : ROAD_COLOR;
    for (int k = 0; k + 1 < points.size(); k++) {
      BlockPos a = points.get(k);
      BlockPos b = points.get(k + 1);
      if (!near(a, camera) && !near(b, camera)) {
        continue;
      }
      Vec3 from = Vec3.atBottomCenterOf(a).add(0.0, 0.1, 0.0);
      Vec3 to = Vec3.atBottomCenterOf(b).add(0.0, 0.1, 0.0);

      GuestGizmos.line(from, to, color, 3.0F).setAlwaysOnTop();
      Vec3 side = new Vec3(to.z - from.z, 0.0, from.x - to.x).normalize().scale(half);
      GuestGizmos.line(from.add(side), to.add(side), ROAD_EDGE_COLOR);
      GuestGizmos.line(from.subtract(side), to.subtract(side), ROAD_EDGE_COLOR);
      GuestGizmos.point(from, color, 6.0F);
    }
    GuestGizmos.point(Vec3.atBottomCenterOf(points.getLast()).add(0.0, 0.1, 0.0), color, 6.0F);
  }

  public static void render() {
    if (!GuestDebug.isEnabled(GuestSettlements.DEBUG_CHANNEL)) {
      return;
    }
    Minecraft minecraft = Minecraft.getInstance();
    MinecraftServer server = minecraft.getSingleplayerServer();
    if (server == null || minecraft.level == null) {
      return;
    }
    ServerLevel level = server.getLevel(minecraft.level.dimension());
    if (level == null) {
      return;
    }
    Vec3 camera = minecraft.gameRenderer.getMainCamera().position();
    List<SocietyData.Camp> camps;
    List<SocietyData.Trace> traces;
    try {
      SocietyData data = SocietyData.get(level);
      camps = List.copyOf(data.camps.values());
      traces = List.copyOf(data.traces.values());
    } catch (ConcurrentModificationException exception) {

      return;
    }
    long now = GuestTime.gameTime(level);
    try (var ignored = minecraft.levelRenderer.collectPerFrameGizmos()) {
      for (SocietyData.Camp camp : camps) {
        if (near(camp.center(), camera)) {
          renderCamp(level, camp);
        }
      }
      for (SocietyData.Trace trace : traces) {
        if (!trace.blocks().isEmpty() && near(trace.blocks().getFirst().pos(), camera)) {
          renderTrace(trace, now);
        }
      }
      renderPeople(level, camera);
    }
  }

  private static void renderCamp(ServerLevel level, SocietyData.Camp camp) {
    GuestGizmos.box(camp.box(), CAMP_COLOR);
    camp.pen()
        .ifPresent(pen -> GuestGizmos.box(new AABB(pen).expandTowards(8.0, 1.0, 8.0), PEN_COLOR));
    List<Component> lines =
        List.of(
            Component.translatable(
                "guest_settlements.debug.camp",
                Component.translatable(
                    "guest_settlements.camp." + camp.kind().getSerializedName())),
            Component.translatable(
                "guest_settlements.debug.camp.people",
                camp.evokers(),
                camp.crew(),
                camp.ravagers(),
                camp.pending()),
            Component.translatable(
                "guest_settlements.debug.camp.prestige",
                IllagerCamps.prestige(level, camp),
                IllagerCamps.leaderEpoch(level, camp)));
    Vec3 top =
        new Vec3(camp.center().getX() + 0.5, camp.box().maxY() + 2.0, camp.center().getZ() + 0.5);
    for (int i = 0; i < lines.size(); i++) {
      GuestGizmos.text(
          lines.get(i), top.add(0.0, (lines.size() - i) * LINE_HEIGHT, 0.0), TEXT_COLOR);
    }
  }

  private static void renderTrace(SocietyData.Trace trace, long now) {
    BlockPos first = trace.blocks().getFirst().pos();
    for (SocietyData.TracedBlock block : trace.blocks()) {
      GuestGizmos.box(new AABB(block.pos()), TRACE_COLOR, 1.0F);
    }
    String left =
        trace.lifetime() < 0
            ? "-"
            : String.format(
                Locale.ROOT,
                "%.1f",
                (trace.time() + trace.lifetime() - now) / (double) GuestTime.TICKS_PER_DAY);
    GuestGizmos.text(
        Component.translatable(
            "guest_settlements.debug.trace",
            Component.translatable("guest_settlements.trace." + trace.kind()),
            trace.blocks().size(),
            left),
        Vec3.atCenterOf(first).add(0.0, 1.5, 0.0),
        TRACE_COLOR);
  }

  private static void renderPeople(ServerLevel level, Vec3 camera) {
    Map<UUID, IllagerRespect.Attitude> attitudes = IllagerRespect.seen();
    for (Map.Entry<UUID, IllagerCamps.Job> entry : IllagerCamps.jobs().entrySet()) {
      Entity entity = level.getEntity(entry.getKey());
      if (entity == null || entity.position().distanceToSqr(camera) > LABEL_DISTANCE_SQR) {
        continue;
      }
      IllagerCamps.Job job = entry.getValue();
      Component label =
          Component.translatable(
              "guest_settlements.debug.job." + job.kind().name().toLowerCase(Locale.ROOT));
      IllagerRespect.Attitude attitude = attitudes.get(entry.getKey());
      if (attitude != null) {
        label =
            Component.translatable(
                "guest_settlements.debug.job.attitude",
                label,
                Component.translatable(
                    "guest_settlements.debug.attitude."
                        + attitude.name().toLowerCase(Locale.ROOT)));
      }
      GuestGizmos.text(
          label, entity.position().add(0.0, entity.getBbHeight() + 0.6, 0.0), TEXT_COLOR);
      GuestGizmos.line(
          entity.position().add(0.0, 0.2, 0.0),
          Vec3.atBottomCenterOf(job.site()).add(0.0, 0.2, 0.0),
          CAMP_COLOR);
    }
    for (Map.Entry<UUID, IllagerRespect.Attitude> entry : attitudes.entrySet()) {
      Entity entity = level.getEntity(entry.getKey());
      if (entity != null
          && !IllagerCamps.jobs().containsKey(entry.getKey())
          && entity.position().distanceToSqr(camera) <= LABEL_DISTANCE_SQR) {
        GuestGizmos.text(
            Component.translatable(
                "guest_settlements.debug.attitude."
                    + entry.getValue().name().toLowerCase(Locale.ROOT)),
            entity.position().add(0.0, entity.getBbHeight() + 0.6, 0.0),
            TEXT_COLOR);
      }
    }
    for (Map.Entry<UUID, Long> entry : Emigration.walking(level).entrySet()) {
      Entity entity = level.getEntity(entry.getKey());
      if (entity != null && entity.position().distanceToSqr(camera) <= LABEL_DISTANCE_SQR) {
        GuestGizmos.text(
            Component.translatable(
                "guest_settlements.debug.emigrant", BlockPos.of(entry.getValue()).toShortString()),
            entity.position().add(0.0, entity.getBbHeight() + 0.6, 0.0),
            EMIGRANT_COLOR);
      }
    }
  }

  private static boolean near(BlockPos pos, Vec3 camera) {
    return pos.distToCenterSqr(camera) <= MAX_DISTANCE_SQR;
  }
}
