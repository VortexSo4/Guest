package com.vortexso.guest_hands.station;

import com.mojang.math.Axis;
import com.mojang.math.Transformation;
import com.vortexso.guest_hands.mixin.DisplayAccessor;
import com.vortexso.guest_hands.mixin.ItemDisplayAccessor;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

public final class StationDisplays {
  public static final String MARKER = "guest_hands";
  private static final String PREFIX = "guest_hands:";

  private StationDisplays() {}

  public record Key(String station, String role, BlockPos pos) {
    String tag() {
      return PREFIX + station + ":" + role + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public static @Nullable Key of(Display display) {
      for (String tag : display.entityTags()) {
        if (!tag.startsWith(PREFIX)) {
          continue;
        }
        String[] parts = tag.split(":");
        String[] xyz = parts.length == 4 ? parts[3].split(",") : new String[0];
        if (xyz.length != 3) {
          return null;
        }
        try {
          return new Key(
              parts[1],
              parts[2],
              new BlockPos(
                  Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])));
        } catch (NumberFormatException exception) {
          return null;
        }
      }
      return null;
    }
  }

  public static Map<String, Display> byRole(ServerLevel level, BlockPos pos, String station) {
    Map<String, Display> result = new HashMap<>();
    AABB area = new AABB(pos).expandTowards(0.0, 2.0, 0.0).inflate(0.5);
    for (Display display :
        level.getEntitiesOfClass(
            Display.class, area, display -> display.entityTags().contains(MARKER))) {
      Key key = Key.of(display);
      if (key == null || !key.pos().equals(pos) || !key.station().equals(station)) {
        continue;
      }
      if (result.putIfAbsent(key.role(), display) != null) {
        display.discard();
      }
    }
    return result;
  }

  public static ItemStack item(Map<String, Display> displays, String role) {
    return displays.get(role) instanceof Display.ItemDisplay display
        ? ((ItemDisplayAccessor) display).guest_hands$getItem()
        : ItemStack.EMPTY;
  }

  public static void setItem(
      ServerLevel level,
      Map<String, Display> displays,
      Key key,
      ItemStack stack,
      Vec3 at,
      Transformation transform) {
    Display existing = displays.get(key.role());
    if (stack.isEmpty()) {
      if (existing != null) {
        existing.discard();
        displays.remove(key.role());
      }
      return;
    }
    Display.ItemDisplay display;
    if (existing instanceof Display.ItemDisplay item) {
      display = item;
      display.setPos(at);
    } else {
      if (existing != null) {
        existing.discard();
      }
      display = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, level);
      display.setPos(at);
      display.addTag(MARKER);
      display.addTag(key.tag());
      ((ItemDisplayAccessor) display).guest_hands$setItemTransform(ItemDisplayContext.FIXED);
      level.addFreshEntity(display);
      displays.put(key.role(), display);
    }
    ItemDisplayAccessor item = (ItemDisplayAccessor) display;
    if (!ItemStack.matches(item.guest_hands$getItem(), stack)) {
      item.guest_hands$setItem(stack.copy());
    }
    ((DisplayAccessor) display).guest_hands$setTransformation(transform);
  }

  public static Transformation lying(
      ItemStack stack, float cubeScale, float flatScale, Direction away) {
    if (isCube(stack)) {
      return new Transformation(
          new Vector3f(0.0F, cubeScale * 0.25F, 0.0F),
          null,
          new Vector3f(cubeScale, cubeScale, cubeScale),
          null);
    }

    Quaternionf rotation =
        Axis.YP.rotationDegrees(180.0F - away.toYRot()).mul(Axis.XP.rotationDegrees(-90.0F));
    return new Transformation(
        new Vector3f(0.0F, flatScale / 32.0F, 0.0F),
        rotation,
        new Vector3f(flatScale, flatScale, flatScale),
        null);
  }

  public static Transformation upright(float scale, float yaw) {
    return new Transformation(
        null, Axis.YP.rotationDegrees(yaw), new Vector3f(scale, scale, scale), null);
  }

  static boolean isCube(ItemStack stack) {
    if (!(stack.getItem() instanceof BlockItem item)) {
      return false;
    }
    BlockState state = item.getBlock().defaultBlockState();
    return state.canOcclude()
        || state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
  }

  public static void clear(Map<String, Display> displays) {
    displays.values().forEach(Display::discard);
    displays.clear();
  }
}
