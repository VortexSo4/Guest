package com.vortexso.guest_core.client;

import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class GuestGizmos {
  public static final float DEFAULT_LINE_WIDTH = 2.0F;
  public static final float DEFAULT_POINT_SIZE = 2.0F;
  public static final float DEFAULT_TEXT_SCALE = 0.32F;

  private GuestGizmos() {}

  public static GizmoProperties box(AABB box, int color) {
    return box(box, color, DEFAULT_LINE_WIDTH);
  }

  public static GizmoProperties box(AABB box, int color, float width) {
    return Gizmos.cuboid(box, GizmoStyle.stroke(color, width));
  }

  public static GizmoProperties box(BoundingBox box, int color) {
    return box(box, color, DEFAULT_LINE_WIDTH);
  }

  public static GizmoProperties box(BoundingBox box, int color, float width) {
    return box(
        new AABB(
            box.minX(),
            box.minY(),
            box.minZ(),
            box.maxX() + 1.0,
            box.maxY() + 1.0,
            box.maxZ() + 1.0),
        color,
        width);
  }

  public static GizmoProperties box(AABB box, int strokeColor, float strokeWidth, int fillColor) {
    return Gizmos.cuboid(box, GizmoStyle.strokeAndFill(strokeColor, strokeWidth, fillColor));
  }

  public static GizmoProperties box(
      BoundingBox box, int strokeColor, float strokeWidth, int fillColor) {
    return box(
        new AABB(
            box.minX(),
            box.minY(),
            box.minZ(),
            box.maxX() + 1.0,
            box.maxY() + 1.0,
            box.maxZ() + 1.0),
        strokeColor,
        strokeWidth,
        fillColor);
  }

  public static GizmoProperties line(Vec3 start, Vec3 end, int color) {
    return line(start, end, color, DEFAULT_LINE_WIDTH);
  }

  public static GizmoProperties line(Vec3 start, Vec3 end, int color, float width) {
    return Gizmos.line(start, end, color, width);
  }

  public static GizmoProperties arrow(Vec3 start, Vec3 end, int color) {
    return Gizmos.arrow(start, end, color, DEFAULT_LINE_WIDTH);
  }

  public static GizmoProperties arrow(Vec3 start, Vec3 end, int color, float width) {
    return Gizmos.arrow(start, end, color, width);
  }

  public static GizmoProperties point(Vec3 position, int color) {
    return point(position, color, DEFAULT_POINT_SIZE);
  }

  public static GizmoProperties point(Vec3 position, int color, float size) {
    return Gizmos.point(position, color, size);
  }

  public static GizmoProperties text(String text, Vec3 position, int color) {
    return text(text, position, color, DEFAULT_TEXT_SCALE);
  }

  public static GizmoProperties text(String text, Vec3 position, int color, float scale) {
    return Gizmos.billboardText(
        text, position, TextGizmo.Style.forColorAndCentered(color).withScale(scale));
  }

  /** Resolves the component with the client language, so debug labels stay localizable. */
  public static GizmoProperties text(Component text, Vec3 position, int color) {
    return text(text.getString(), position, color);
  }

  public static GizmoProperties text(
      String text, Vec3 position, int color, float scale, boolean alwaysOnTop) {
    GizmoProperties properties = text(text, position, color, scale);

    if (alwaysOnTop) {
      properties.setAlwaysOnTop();
    }

    return properties;
  }
}
