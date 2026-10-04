package com.vortexso.guest_architects.client;

import com.vortexso.guest_architects.GuestArchitects;
import com.vortexso.guest_architects.city.CityManager;
import com.vortexso.guest_core.client.GuestGizmos;
import com.vortexso.guest_core.debug.GuestDebug;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

public final class ArchitectsDebugRenderer {
  private static final double MAX_DISTANCE_SQR = 256.0 * 256.0;
  private static final int CITY_COLOR = 0xFF3FA7A0;
  private static final int RITUAL_COLOR = 0xFF55FFFF;
  private static final int PORTAL_COLOR = 0xFF8866FF;
  private static final int ORIGIN_COLOR = 0xFF22DD88;
  private static final int DORMANT_COLOR = 0xFF557766;
  private static final int MEMORY_COLOR = 0xFFE8D08A;
  private static final int TARGET_COLOR = 0xFFFFDD55;
  private static final int TEXT_COLOR = 0xFFFFFFFF;
  private static final double LINE_HEIGHT = 0.25;

  private ArchitectsDebugRenderer() {}

  public static void render() {
    if (!GuestDebug.isEnabled(GuestArchitects.DEBUG_CHANNEL)) {
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
    CityManager.DebugSnapshot snapshot = CityManager.get(level).debugSnapshot();
    Vec3 camera = minecraft.gameRenderer.getMainCamera().position();

    try (var ignored = minecraft.levelRenderer.collectPerFrameGizmos()) {
      for (CityManager.DebugCity city : snapshot.cities()) {
        if (city.box().getCenter().distToCenterSqr(camera) > MAX_DISTANCE_SQR) {
          continue;
        }
        renderCity(city);
      }
      for (CityManager.DebugArchitect architect : snapshot.architects()) {
        if (architect.position().distanceToSqr(camera) > MAX_DISTANCE_SQR) {
          continue;
        }
        renderArchitect(architect);
      }
    }
  }

  private static void renderCity(CityManager.DebugCity city) {
    GuestGizmos.box(city.box(), CITY_COLOR);
    for (BoundingBox ritual : city.ritualBoxes()) {
      GuestGizmos.box(ritual, RITUAL_COLOR);
    }
    GuestGizmos.box(city.portalBox(), PORTAL_COLOR);
    if (city.memoryBox() != null) {
      GuestGizmos.box(city.memoryBox(), MEMORY_COLOR);
    }
    for (BlockPos origin : city.origins()) {
      GuestGizmos.point(
          origin.getCenter(), city.livingStructure() ? DORMANT_COLOR : ORIGIN_COLOR, 5.0F);
    }
    for (BlockPos kept : city.ritualOrigins()) {
      GuestGizmos.point(kept.getCenter(), ORIGIN_COLOR, 9.0F);
    }

    List<Component> lines = new ArrayList<>();
    lines.add(Component.translatable("guest_architects.debug.city", city.id()));
    boolean inhabited = city.livingStructure() && city.population() > 0;
    lines.add(
        Component.translatable(
            inhabited
                ? "guest_architects.debug.living"
                : city.livingStructure()
                    ? "guest_architects.debug.extinct"
                    : "guest_architects.debug.abandoned"));
    if (city.livingStructure() && city.state() != null) {
      lines.add(
          Component.translatable(
              "guest_architects.debug.state",
              Component.translatable(
                  "guest_architects.state." + city.state().name().toLowerCase(Locale.ROOT))));
    }
    lines.add(Component.translatable("guest_architects.debug.population", city.population()));
    if (city.livingStructure() && !inhabited) {
      lines.add(
          Component.translatable(
              "guest_architects.debug.converted", city.converted(), city.pieces()));
    }
    lines.add(
        Component.translatable(
            "guest_architects.debug.delay", String.format(Locale.ROOT, "%.2f", city.delayDays())));
    lines.add(
        Component.translatable(
            "guest_architects.debug.sculk", city.spreadRadius(), city.strays(), city.damage()));
    lines.add(
        Component.translatable(
            "guest_architects.debug.recognition",
            Component.translatable(
                city.recognized() ? "guest_core.common.yes" : "guest_core.common.no")));
    for (String relation : city.relations()) {
      lines.add(Component.translatable("guest_architects.debug.relation", relation));
    }

    Vec3 top = city.portalBox().getCenter().getCenter();
    double y = city.portalBox().maxY() + 2.0;
    for (int i = 0; i < lines.size(); i++) {
      GuestGizmos.text(
          lines.get(i), new Vec3(top.x, y + (lines.size() - i) * LINE_HEIGHT, top.z), TEXT_COLOR);
    }
  }

  private static void renderArchitect(CityManager.DebugArchitect architect) {
    Vec3 base = architect.position().add(0, architect.height() + 0.4, 0);
    GuestGizmos.text(
        Component.translatable(
            "guest_architects.debug.architect",
            architect.index(),
            architect.role().displayName(),
            Component.translatable("guest_architects.activity." + architect.activity())),
        base.add(0, LINE_HEIGHT, 0),
        TEXT_COLOR);
    GuestGizmos.text(
        Component.translatable("guest_architects.debug.city", architect.cityId()),
        base,
        TEXT_COLOR);
    if (architect.target() != null) {
      GuestGizmos.arrow(architect.position().add(0, 1.0, 0), architect.target(), TARGET_COLOR);
    }
  }
}
