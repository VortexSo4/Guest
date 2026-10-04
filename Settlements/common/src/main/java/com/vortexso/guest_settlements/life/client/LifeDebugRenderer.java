package com.vortexso.guest_settlements.life.client;

import com.vortexso.guest_core.client.GuestGizmos;
import com.vortexso.guest_core.debug.GuestDebug;
import com.vortexso.guest_settlements.GuestSettlements;
import com.vortexso.guest_settlements.life.Construction;
import com.vortexso.guest_settlements.life.VillageLife;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class LifeDebugRenderer {
  private static final double RANGE_SQR = 256.0 * 256.0;
  private static final int PROJECT_COLOR = 0xFFFF8800;
  private static final int NEXT_COLOR = 0xFFFFFF00;
  private static final int TARGET_COLOR = 0xFF88CCFF;
  private static final int TEXT_COLOR = 0xFFFFFFFF;

  private LifeDebugRenderer() {}

  public static void render() {
    Minecraft minecraft = Minecraft.getInstance();
    if (!GuestDebug.isEnabled(GuestSettlements.DEBUG_CHANNEL) || minecraft.level == null) {
      return;
    }
    VillageLife.DebugView view = VillageLife.debug(minecraft.level.dimension());
    if (view == null) {
      return;
    }
    Vec3 camera = minecraft.gameRenderer.getMainCamera().position();
    try (var ignored = minecraft.levelRenderer.collectPerFrameGizmos()) {
      for (Construction.View project : view.projects()) {
        Vec3 top =
            new Vec3(
                project.extent().getCenter().getX() + 0.5,
                project.extent().maxY() + 1.5,
                project.extent().getCenter().getZ() + 0.5);
        if (top.distanceToSqr(camera) > RANGE_SQR) {
          continue;
        }
        GuestGizmos.box(project.extent(), PROJECT_COLOR);
        if (project.next() != null) {
          GuestGizmos.box(new AABB(project.next()), NEXT_COLOR);
        }
        GuestGizmos.text(
            Component.translatable(
                "guest_settlements.debug.project",
                Component.translatable(
                    "guest_settlements.project." + project.kind().toLowerCase(Locale.ROOT)),
                project.name(),
                project.placed(),
                project.total(),
                project.wood(),
                project.stone()),
            top,
            PROJECT_COLOR);
      }
      for (Vec3[] line : view.targets()) {
        if (line[0].distanceToSqr(camera) < 64.0 * 64.0) {
          GuestGizmos.line(line[0], line[1], TARGET_COLOR);
        }
      }
      for (Vec3 at : view.away()) {
        if (at.distanceToSqr(camera) < RANGE_SQR) {
          GuestGizmos.text(Component.translatable("guest_settlements.debug.away"), at, TEXT_COLOR);
        }
      }
    }
  }
}
