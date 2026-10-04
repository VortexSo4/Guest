package com.vortexso.guest_wilds.behavior;

import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.Season;
import com.vortexso.guest_wilds.Ecology;
import com.vortexso.guest_wilds.GuestWilds;
import com.vortexso.guest_wilds.WildsConfig;
import com.vortexso.guest_wilds.WildsParameters;
import com.vortexso.guest_wilds.lair.LairSpecies;
import com.vortexso.guest_wilds.lair.Lairs;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.chicken.ChickenVariant;
import net.minecraft.world.entity.animal.chicken.ChickenVariants;
import net.minecraft.world.entity.animal.fox.Fox;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

public final class Variants {
  private static final long MOLT_SALT = 0x6D6F6C74L;
  private static final int MOLT_SPREAD_DAYS = 8;

  private Variants() {}

  public static EntityType<? extends Mob> lairForm(
      LairSpecies species, ServerLevel level, BlockPos pos, long now, Lairs.@Nullable Node lair) {
    EntityType<? extends Mob> base =
        switch (species) {
          case ZOMBIE -> EntityType.ZOMBIE;
          case SKELETON -> EntityType.SKELETON;
          case SPIDER -> EntityType.SPIDER;
          case CREEPER -> EntityType.CREEPER;
        };
    if (!WildsConfig.SEASONAL_VARIANTS.get()) {
      return base;
    }
    BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos);
    Holder<Biome> biome = level.getBiome(surface);
    Season season = GuestTime.season(now);
    return switch (species) {
      case ZOMBIE -> husky(biome, season) ? EntityType.HUSK : EntityType.ZOMBIE;
      case SKELETON -> {
        if (lair != null
            && (biome.is(Biomes.SWAMP) || biome.is(Biomes.MANGROVE_SWAMP))
            && (now - lair.founded) / (double) Ecology.TICKS_PER_DAY
                >= WildsParameters.DEFAULT.boggedResidenceDays()) {
          yield EntityType.BOGGED;
        }
        if (cold(level, biome, surface, season)) {
          yield EntityType.STRAY;
        }

        yield husky(biome, season) ? EntityType.PARCHED : EntityType.SKELETON;
      }
      default -> base;
    };
  }

  public static @Nullable EntityType<? extends Mob> naturalForm(
      Mob mob, ServerLevel level, BlockPos pos, long now) {
    LairSpecies species = LairSpecies.of(mob);
    if (species != LairSpecies.ZOMBIE && species != LairSpecies.SKELETON) {
      return null;
    }
    if (mob.getType() == EntityType.BOGGED) {
      return null;
    }
    EntityType<? extends Mob> form = lairForm(species, level, pos, now, null);
    return form == mob.getType() ? null : form;
  }

  private static boolean husky(Holder<Biome> biome, Season season) {
    float temperature = biome.value().getBaseTemperature();
    boolean hotDry = !biome.value().hasPrecipitation() && temperature > 1.0F;
    return (hotDry && season != Season.WINTER) || (season == Season.SUMMER && temperature >= 1.0F);
  }

  private static boolean cold(ServerLevel level, Holder<Biome> biome, BlockPos pos, Season season) {
    return biome.value().coldEnoughToSnow(pos, level.getSeaLevel())
        || (season == Season.WINTER && biome.value().getBaseTemperature() <= 0.8F);
  }

  public static void onNewAnimal(ServerLevel level, Entity entity) {
    if (!(entity instanceof Chicken chicken) || !WildsConfig.SEASONAL_VARIANTS.get()) {
      return;
    }
    Holder<Biome> biome = level.getBiome(entity.blockPosition());
    if (biome.is(BiomeTags.SPAWNS_COLD_VARIANT_FARM_ANIMALS)
        || biome.is(BiomeTags.SPAWNS_WARM_VARIANT_FARM_ANIMALS)) {
      return;
    }
    ResourceKey<ChickenVariant> variant =
        switch (GuestTime.season(GuestTime.gameTime(level))) {
          case WINTER -> ChickenVariants.COLD;
          case SUMMER -> ChickenVariants.WARM;
          default -> null;
        };
    if (variant != null) {
      level
          .registryAccess()
          .lookupOrThrow(Registries.CHICKEN_VARIANT)
          .get(variant)
          .ifPresent(holder -> chicken.setVariant(holder));
    }
  }

  public static void updateCoat(ServerLevel level, Entity entity) {
    if (!WildsConfig.SEASONAL_VARIANTS.get() || entity.hasCustomName()) {
      return;
    }
    if (!(entity instanceof Rabbit) && !(entity instanceof Fox)) {
      return;
    }
    Holder<Biome> biome = level.getBiome(entity.blockPosition());
    boolean winter = winterCoat(entity.getUUID(), GuestTime.gameTime(level));
    String summer = GuestWilds.SUMMER_COAT.get(entity);
    boolean molted = !summer.isEmpty();
    if (entity instanceof Rabbit rabbit && !biome.is(BiomeTags.SPAWNS_WHITE_RABBITS)) {
      Rabbit.Variant variant = rabbit.getVariant();
      if (winter && !molted && variant != Rabbit.Variant.WHITE && variant != Rabbit.Variant.EVIL) {
        GuestWilds.SUMMER_COAT.set(entity, variant.name());
        rabbit.setComponent(DataComponents.RABBIT_VARIANT, Rabbit.Variant.WHITE);
      } else if (!winter && molted) {
        rabbit.setComponent(DataComponents.RABBIT_VARIANT, Rabbit.Variant.valueOf(summer));
        GuestWilds.SUMMER_COAT.set(entity, "");
      }
    } else if (entity instanceof Fox fox && !biome.is(BiomeTags.SPAWNS_SNOW_FOXES)) {
      if (winter && !molted && fox.getVariant() == Fox.Variant.RED) {
        GuestWilds.SUMMER_COAT.set(entity, Fox.Variant.RED.name());
        fox.setComponent(DataComponents.FOX_VARIANT, Fox.Variant.SNOW);
      } else if (!winter && molted) {
        fox.setComponent(DataComponents.FOX_VARIANT, Fox.Variant.RED);
        GuestWilds.SUMMER_COAT.set(entity, "");
      }
    }
  }

  static boolean winterCoat(UUID id, long now) {
    int offset =
        (int)
            (GuestHash.unit(
                    GuestHash.hash(
                        id.getMostSignificantBits(), id.getLeastSignificantBits(), MOLT_SALT))
                * MOLT_SPREAD_DAYS);
    int winterStart = 3 * GuestTime.DAYS_PER_SEASON;
    int day =
        Math.floorMod(GuestTime.dayOfYear(now) - winterStart - offset, GuestTime.DAYS_PER_YEAR);
    return day < GuestTime.DAYS_PER_SEASON;
  }
}
