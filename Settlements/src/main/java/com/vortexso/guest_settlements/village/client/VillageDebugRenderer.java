package com.vortexso.guest_settlements.village.client;

import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.client.GuestGizmos;
import com.vortexso.guest_core.debug.GuestDebug;
import com.vortexso.guest_settlements.GuestSettlements;
import com.vortexso.guest_settlements.life.VillageLife;
import com.vortexso.guest_settlements.village.VillageDebugSnapshot;
import com.vortexso.guest_settlements.village.VillageSimulator;
import com.vortexso.guest_settlements.village.VillageState;
import com.vortexso.guest_settlements.village.VillageWorldManager;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * {@code /guest debug settlements}: draws the live aggregate of every nearby village, its farms,
 * bell, roads, caravans in transit and the roles villagers currently play. Reads the integrated
 * server directly, so it works in singleplayer only.
 */
@EventBusSubscriber(modid = GuestSettlements.MODID, value = Dist.CLIENT)
public final class VillageDebugRenderer {
  private static final double MAX_DISTANCE_SQR = 512.0 * 512.0;
  private static final int ACTIVE_COLOR = 0xFF55FFFF;
  private static final int SIMULATED_COLOR = 0xFFFFAA55;
  private static final int FALLEN_COLOR = 0xFFFF5555;
  private static final int FARM_COLOR = 0xFF55FF55;
  private static final int ROAD_COLOR = 0xFFFFFF55;
  private static final int CARAVAN_COLOR = 0xFFFF55FF;
  private static final int BELL_COLOR = 0xFFFFD700;
  private static final int TEXT_COLOR = 0xFFFFFFFF;
  private static final double LINE_HEIGHT = 0.3;

  private VillageDebugRenderer() {}

  @SubscribeEvent
  public static void render(RenderLevelStageEvent.AfterTranslucentBlocks event) {
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
    VillageDebugSnapshot snapshot;
    try {
      snapshot = VillageWorldManager.get(level).snapshot();
    } catch (ConcurrentModificationException exception) {
      // Read from the render thread while the server thread mutates; just skip this frame.
      return;
    }
    Vec3 camera = minecraft.gameRenderer.getMainCamera().position();

    try (var ignored = minecraft.levelRenderer.collectPerFrameGizmos()) {
      for (VillageDebugSnapshot.VillageSnapshot village : snapshot.villages()) {
        if (village.center().distToCenterSqr(camera) <= MAX_DISTANCE_SQR) {
          renderVillage(village, snapshot.day());
        }
      }
      for (VillageDebugSnapshot.RoadSnapshot road : snapshot.roads()) {
        if (road.firstCenter().distToCenterSqr(camera) <= MAX_DISTANCE_SQR
            || road.secondCenter().distToCenterSqr(camera) <= MAX_DISTANCE_SQR) {
          GuestGizmos.line(
              road.firstCenter().getCenter().add(0.0, 0.5, 0.0),
              road.secondCenter().getCenter().add(0.0, 0.5, 0.0),
              ROAD_COLOR);
        }
      }
      for (VillageDebugSnapshot.CaravanSnapshot caravan : snapshot.caravans()) {
        if (caravan.position().distanceToSqr(camera) <= MAX_DISTANCE_SQR) {
          GuestGizmos.box(
              new AABB(caravan.position(), caravan.position()).inflate(1.0), CARAVAN_COLOR);
          GuestGizmos.text(
              Component.translatable(
                  caravan.lost()
                      ? "guest_settlements.debug.caravan.lost"
                      : "guest_settlements.debug.caravan",
                  percent(caravan.progress())),
              caravan.position().add(0.0, 2.0, 0.0),
              CARAVAN_COLOR);
        }
      }
      renderRoles(level, camera);
    }
  }

  private static void renderVillage(VillageDebugSnapshot.VillageSnapshot village, long day) {
    VillageState state = village.state();
    int color =
        state != null && state.fallen()
            ? FALLEN_COLOR
            : village.active() ? ACTIVE_COLOR : SIMULATED_COLOR;
    if (village.structureBox() != null) {
      GuestGizmos.box(village.structureBox(), color);
    } else {
      GuestGizmos.point(village.center().getCenter(), color);
    }
    if (village.bell() != null) {
      GuestGizmos.box(new AABB(village.bell()), BELL_COLOR);
    }
    for (VillageDebugSnapshot.FarmSnapshot farm : village.farms()) {
      BoundingBox box = farm.farmBox();
      if (box != null) {
        GuestGizmos.box(box, FARM_COLOR);
        GuestGizmos.text(
            Component.translatable(
                farm.complete()
                    ? "guest_settlements.debug.farm"
                    : "guest_settlements.debug.farm.partial",
                farm.farmlandAmount()),
            new Vec3(
                (box.minX() + box.maxX() + 1) * 0.5,
                box.maxY() + 1.5,
                (box.minZ() + box.maxZ() + 1) * 0.5),
            TEXT_COLOR);
      }
    }

    List<Component> lines = new ArrayList<>();
    lines.add(
        Component.translatable(
            village.active()
                ? "guest_settlements.debug.village.active"
                : "guest_settlements.debug.village.simulated",
            Long.toHexString(village.id())));
    if (state != null) {
      lines.add(
          Component.translatable(
              "guest_settlements.debug.population", state.population(), state.children()));
      lines.add(
          Component.translatable(
              "guest_settlements.debug.housing", state.beds(), state.farmland()));
      VillageSimulator.Rates rates = village.rates();
      if (rates != null) {
        lines.add(
            Component.translatable(
                "guest_settlements.debug.food",
                format(state.foodReserve()),
                format(rates.production()),
                format(rates.consumption())));
        lines.add(
            Component.translatable(
                "guest_settlements.debug.rates", format(rates.births()), format(rates.deaths())));
      }
      lines.add(
          Component.translatable("guest_settlements.debug.pressure", format(village.pressure())));
      lines.add(ceremony(state, day));
      if (state.fallen()) {
        lines.add(Component.translatable("guest_settlements.debug.fallen", state.infected()));
      }
      for (Map.Entry<Identifier, Integer> entry : state.professions().entrySet()) {
        lines.add(
            Component.translatable(
                "guest_settlements.debug.profession",
                Component.translatable(
                    "entity."
                        + entry.getKey().getNamespace()
                        + ".villager."
                        + entry.getKey().getPath()),
                entry.getValue()));
      }
    }

    Vec3 center = village.center().getCenter();
    double y =
        village.structureBox() != null ? village.structureBox().maxY() + 1.5 : center.y + 1.5;
    for (int i = 0; i < lines.size(); i++) {
      GuestGizmos.text(
          lines.get(i),
          new Vec3(center.x, y + (lines.size() - i) * LINE_HEIGHT, center.z),
          TEXT_COLOR);
    }
  }

  private static Component ceremony(VillageState state, long day) {
    int weekday = (int) Math.floorMod(day, GuestTime.DAYS_PER_WEEK);
    if (weekday == 4 && VillageLife.mourningDue(state, day)) {
      return Component.translatable("guest_settlements.debug.ceremony.mourning");
    }
    int untilRite = (GuestTime.DAYS_PER_WEEK - weekday) % GuestTime.DAYS_PER_WEEK;
    return untilRite == 0
        ? Component.translatable("guest_settlements.debug.ceremony.tonight")
        : Component.translatable("guest_settlements.debug.ceremony.in", untilRite);
  }

  private static void renderRoles(ServerLevel level, Vec3 camera) {
    for (Map.Entry<UUID, VillageLife.Role> entry : VillageLife.roles(level).entrySet()) {
      Entity entity = level.getEntity(entry.getKey());
      if (entity != null && entity.position().distanceToSqr(camera) <= 64.0 * 64.0) {
        GuestGizmos.text(
            Component.translatable(
                "guest_settlements.debug.role." + entry.getValue().name().toLowerCase(Locale.ROOT)),
            entity.position().add(0.0, entity.getBbHeight() + 0.6, 0.0),
            TEXT_COLOR);
      }
    }
  }

  private static String format(double value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }

  private static String percent(double value) {
    return String.format(Locale.ROOT, "%.0f", value * 100.0);
  }
}
