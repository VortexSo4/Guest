package com.vortexso.guest_hands.station;

import com.mojang.math.Axis;
import com.mojang.math.Transformation;
import com.vortexso.guest_hands.mixin.DisplayAccessor;
import com.vortexso.guest_hands.mixin.ItemDisplayAccessor;
import com.vortexso.guest_hands.mixin.TextDisplayAccessor;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Vanilla display entities anchored to a workstation block. Each carries two tags: {@link #MARKER}
 * and {@code guest_hands:<station>:<role>:<x>,<y>,<z>}. For crafting tables, anvils and enchanting
 * tables (no block entity) the item display <em>is</em> the storage, saved with the chunk's
 * entities like an item frame; for furnaces and brewing stands it only mirrors the block entity.
 */
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

  /** Displays of one station keyed by role. Duplicates (should never exist) are discarded. */
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

  /**
   * Shows {@code stack} at {@code at}; an empty stack removes the display. Unchanged stacks are not
   * re-sent (ItemStack has no equals, so the synced data would resend it every time).
   */
  public static void setItem(
      ServerLevel level,
      Map<String, Display> displays,
      Key key,
      ItemStack stack,
      Vec3 at,
      float scale,
      boolean flat) {
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
      if (ItemStack.matches(((ItemDisplayAccessor) item).guest_hands$getItem(), stack)) {
        return;
      }
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
    ((ItemDisplayAccessor) display).guest_hands$setItem(stack.copy());
    ((DisplayAccessor) display).guest_hands$setTransformation(itemTransform(stack, scale, flat));
  }

  /** Flat items lie on the surface; block items stand as a small cube resting on it. */
  private static Transformation itemTransform(ItemStack stack, float scale, boolean flat) {
    boolean block = stack.getItem() instanceof BlockItem;
    Quaternionf rotation = flat && !block ? Axis.XP.rotationDegrees(-90.0F) : new Quaternionf();
    float lift = block ? scale * 0.25F : 0.0F;
    return new Transformation(
        new Vector3f(0.0F, lift, 0.0F), rotation, new Vector3f(scale, scale, scale), null);
  }

  /** Shows a billboard label; {@code null} removes it. Equal text is not re-sent. */
  public static void setText(
      ServerLevel level,
      Map<String, Display> displays,
      Key key,
      @Nullable Component text,
      Vec3 at) {
    Display existing = displays.get(key.role());
    if (text == null) {
      if (existing != null) {
        existing.discard();
        displays.remove(key.role());
      }
      return;
    }
    if (existing instanceof Display.TextDisplay label) {
      if (!Objects.equals(((TextDisplayAccessor) label).guest_hands$getText(), text)) {
        ((TextDisplayAccessor) label).guest_hands$setText(text);
      }
      return;
    }
    if (existing != null) {
      existing.discard();
    }
    Display.TextDisplay label = new Display.TextDisplay(EntityType.TEXT_DISPLAY, level);
    label.setPos(at);
    label.addTag(MARKER);
    label.addTag(key.tag());
    ((DisplayAccessor) label).guest_hands$setBillboard(Display.BillboardConstraints.CENTER);
    ((DisplayAccessor) label)
        .guest_hands$setTransformation(
            new Transformation(null, null, new Vector3f(0.5F, 0.5F, 0.5F), null));
    ((TextDisplayAccessor) label).guest_hands$setText(text);
    level.addFreshEntity(label);
    displays.put(key.role(), label);
  }

  public static void clear(Map<String, Display> displays) {
    displays.values().forEach(Display::discard);
    displays.clear();
  }
}
