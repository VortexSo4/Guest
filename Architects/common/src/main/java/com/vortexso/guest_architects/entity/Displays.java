package com.vortexso.guest_architects.entity;

import com.mojang.math.Transformation;
import com.mojang.serialization.Codec;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

public final class Displays {
  private Displays() {}

  public static @Nullable Display spawnText(
      ServerLevel level, Vec3 at, float yaw, Component text, float scale, int light, String tag) {
    CompoundTag nbt = new CompoundTag();
    nbt.putString("id", "minecraft:text_display");
    put(nbt, level.registryAccess(), "text", ComponentSerialization.CODEC, text);
    nbt.putInt("line_width", 280);
    nbt.putInt("background", 0);
    put(nbt, level.registryAccess(), "transformation", Transformation.CODEC, scaled(scale, 0));
    put(nbt, level.registryAccess(), "brightness", Brightness.CODEC, new Brightness(light, 0));
    return (Display) spawn(level, nbt, at, yaw, tag);
  }

  public static Display.@Nullable ItemDisplay spawnItem(
      ServerLevel level, Vec3 at, ItemStack stack, float scale, String tag, boolean facePlayer) {
    CompoundTag nbt = new CompoundTag();
    nbt.putString("id", "minecraft:item_display");
    put(nbt, level.registryAccess(), "item", ItemStack.CODEC, stack);
    put(
        nbt,
        level.registryAccess(),
        "item_display",
        ItemDisplayContext.CODEC,
        ItemDisplayContext.FIXED);
    put(nbt, level.registryAccess(), "transformation", Transformation.CODEC, scaled(scale, 0));
    put(nbt, level.registryAccess(), "brightness", Brightness.CODEC, Brightness.FULL_BRIGHT);
    if (facePlayer) {
      nbt.putString("billboard", "center");
    }
    return (Display.ItemDisplay) spawn(level, nbt, at, 0.0F, tag);
  }

  public static void animate(Display display, float scale, float spin, int ticks) {
    merge(
        display,
        tag -> {
          put(
              tag,
              display.registryAccess(),
              "transformation",
              Transformation.CODEC,
              scaled(scale, spin));
          tag.putInt("start_interpolation", 0);
          tag.putInt("interpolation_duration", ticks);
        });
  }

  public static void setText(Display display, Component text) {
    merge(
        display,
        tag -> put(tag, display.registryAccess(), "text", ComponentSerialization.CODEC, text));
  }

  public static void setLight(Display display, int light) {
    merge(
        display,
        tag ->
            put(
                tag,
                display.registryAccess(),
                "brightness",
                Brightness.CODEC,
                new Brightness(light, 0)));
  }

  private static Transformation scaled(float scale, float spin) {
    return new Transformation(
        new Vector3f(), new Quaternionf().rotationY(spin), new Vector3f(scale), new Quaternionf());
  }

  private static @Nullable Entity spawn(
      ServerLevel level, CompoundTag nbt, Vec3 at, float yaw, String tag) {
    Entity entity =
        EntityType.loadEntityRecursive(
            nbt,
            level,
            EntitySpawnReason.COMMAND,
            e -> {
              e.snapTo(at.x, at.y, at.z, yaw, 0.0F);
              return e;
            });
    if (entity == null) {
      return null;
    }
    entity.addTag(tag);
    level.addFreshEntity(entity);
    return entity;
  }

  private static void merge(Entity entity, Consumer<CompoundTag> change) {
    TagValueOutput out =
        TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
    entity.saveWithoutId(out);
    CompoundTag tag = out.buildResult();
    change.accept(tag);
    UUID id = entity.getUUID();
    entity.load(TagValueInput.create(ProblemReporter.DISCARDING, entity.registryAccess(), tag));
    entity.setUUID(id);
  }

  private static <T> void put(
      CompoundTag tag, HolderLookup.Provider registries, String key, Codec<T> codec, T value) {
    tag.put(
        key,
        codec
            .encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), value)
            .getOrThrow());
  }
}
