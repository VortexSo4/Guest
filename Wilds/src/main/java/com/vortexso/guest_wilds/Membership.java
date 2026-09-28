package com.vortexso.guest_wilds;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import org.jspecify.annotations.Nullable;

public record Membership(Kind kind, long node, String species) {
  public enum Kind implements StringRepresentable {
    LAIR,
    HERD,
    SHOAL;

    public static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);

    @Override
    public String getSerializedName() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public static final Membership NONE = new Membership(Kind.LAIR, Long.MIN_VALUE, "");

  public static final MapCodec<Membership> MAP_CODEC =
      RecordCodecBuilder.mapCodec(
          i ->
              i.group(
                      Kind.CODEC.fieldOf("kind").forGetter(Membership::kind),
                      Codec.LONG.fieldOf("node").forGetter(Membership::node),
                      Codec.STRING.fieldOf("species").forGetter(Membership::species))
                  .apply(i, Membership::new));

  public static @Nullable Membership of(Entity entity) {
    return entity.hasData(GuestWilds.MEMBERSHIP) ? entity.getData(GuestWilds.MEMBERSHIP) : null;
  }

  public static <T extends Mob> @Nullable T spawn(
      EntityType<T> type, ServerLevel level, BlockPos at, Membership membership) {
    return spawn(type, level, at, membership, mob -> {});
  }

  public static <T extends Mob> @Nullable T spawn(
      EntityType<T> type,
      ServerLevel level,
      BlockPos at,
      Membership membership,
      Consumer<? super T> configure) {
    return type.spawn(
        level,
        mob -> {
          mob.setData(GuestWilds.MEMBERSHIP, membership);
          configure.accept(mob);
        },
        at,
        EntitySpawnReason.EVENT,
        false,
        false);
  }
}
