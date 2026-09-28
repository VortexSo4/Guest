package com.vortexso.guest_wilds.lair;

import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_wilds.WildsParameters;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

public enum LairSpecies {
  ZOMBIE(0xFF55AA55),
  SKELETON(0xFFDDDDDD),
  SPIDER(0xFFAA3333),
  CREEPER(0xFF33DD33);

  public static final LairSpecies[] VALUES = values();

  public final int color;

  LairSpecies(int color) {
    this.color = color;
  }

  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }

  public double fit(double openness) {
    return switch (this) {
      case SKELETON -> 0.4 + 0.8 * openness;
      case SPIDER -> 1.2 - 0.8 * openness;
      case ZOMBIE -> 0.9;
      case CREEPER -> 0.6;
    };
  }

  public double strength(double openness, double population) {
    return population * fit(openness) * (this == CREEPER ? 0.5 : 1.0);
  }

  public double growth(WildsParameters p) {
    return switch (this) {
      case ZOMBIE -> p.zombieGrowthPerDay();
      case SKELETON -> p.skeletonGrowthPerDay();
      case SPIDER -> p.spiderGrowthPerDay();
      case CREEPER -> p.creeperGrowthPerDay();
    };
  }

  public double settleWeight(Habitat habitat) {
    return switch (this) {
      case ZOMBIE -> 1.0 + (habitat.ruins() ? 3.0 : 0.0);
      case SKELETON -> 0.4 + 2.0 * habitat.openness() + (habitat.depth() >= 12 ? 0.4 : 0.0);
      case SPIDER -> 0.4 + 1.6 * (1.0 - habitat.openness()) + (habitat.forest() ? 1.5 : 0.0);
      case CREEPER -> 0.5 + (habitat.lush() ? 4.0 : 0.0);
    };
  }

  public record Habitat(boolean ruins, boolean forest, boolean lush, double openness, int depth) {}

  public static int settle(long h, Habitat habitat, int exclude) {
    double total = 0.0;
    for (LairSpecies species : VALUES) {
      total += species.ordinal() == exclude ? 0.0 : species.settleWeight(habitat);
    }
    double roll = GuestHash.unit(h) * total;
    int last = -1;
    for (LairSpecies species : VALUES) {
      if (species.ordinal() == exclude) {
        continue;
      }
      last = species.ordinal();
      roll -= species.settleWeight(habitat);
      if (roll < 0.0) {
        return last;
      }
    }
    return last;
  }

  public enum Zone {
    MOUTH,

    DEN
  }

  public enum Kind {
    FLOOR,

    STAND,

    CEILING,

    WALL,

    CRATER
  }

  public record TraceSpec(Zone zone, Kind kind, BlockState state, int count) {}

  private static final class Traces {
    private static final List<TraceSpec> ZOMBIE_TRACES =
        List.of(
            new TraceSpec(Zone.MOUTH, Kind.FLOOR, Blocks.ROOTED_DIRT.defaultBlockState(), 3),
            new TraceSpec(Zone.MOUTH, Kind.STAND, Blocks.OAK_TRAPDOOR.defaultBlockState(), 2),
            new TraceSpec(Zone.MOUTH, Kind.STAND, Blocks.BROWN_MUSHROOM.defaultBlockState(), 1),
            new TraceSpec(Zone.DEN, Kind.FLOOR, Blocks.ROOTED_DIRT.defaultBlockState(), 3),
            new TraceSpec(Zone.DEN, Kind.WALL, Blocks.COBBLESTONE.defaultBlockState(), 3),
            new TraceSpec(Zone.DEN, Kind.STAND, Blocks.BROWN_MUSHROOM.defaultBlockState(), 2));

    private static final List<TraceSpec> SKELETON_TRACES =
        List.of(
            new TraceSpec(Zone.MOUTH, Kind.FLOOR, Blocks.BONE_BLOCK.defaultBlockState(), 2),
            new TraceSpec(Zone.MOUTH, Kind.STAND, Blocks.SKELETON_SKULL.defaultBlockState(), 1),
            new TraceSpec(
                Zone.MOUTH,
                Kind.STAND,
                Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false),
                1),
            new TraceSpec(Zone.DEN, Kind.FLOOR, Blocks.BONE_BLOCK.defaultBlockState(), 3),
            new TraceSpec(Zone.DEN, Kind.FLOOR, Blocks.SUSPICIOUS_GRAVEL.defaultBlockState(), 1),
            new TraceSpec(Zone.DEN, Kind.STAND, Blocks.SKELETON_SKULL.defaultBlockState(), 1),
            new TraceSpec(
                Zone.DEN,
                Kind.STAND,
                Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false),
                1));

    private static final List<TraceSpec> SPIDER_TRACES =
        List.of(
            new TraceSpec(Zone.MOUTH, Kind.STAND, Blocks.COBWEB.defaultBlockState(), 3),
            new TraceSpec(Zone.MOUTH, Kind.CEILING, Blocks.COBWEB.defaultBlockState(), 3),
            new TraceSpec(Zone.DEN, Kind.STAND, Blocks.WHITE_WOOL.defaultBlockState(), 2),
            new TraceSpec(Zone.DEN, Kind.STAND, Blocks.COBWEB.defaultBlockState(), 5),
            new TraceSpec(Zone.DEN, Kind.CEILING, Blocks.COBWEB.defaultBlockState(), 3));

    private static final List<TraceSpec> CREEPER_TRACES =
        List.of(
            new TraceSpec(Zone.MOUTH, Kind.FLOOR, Blocks.BLACKSTONE.defaultBlockState(), 3),
            new TraceSpec(Zone.MOUTH, Kind.CRATER, Blocks.AIR.defaultBlockState(), 1),
            new TraceSpec(Zone.DEN, Kind.STAND, Blocks.TURTLE_EGG.defaultBlockState(), 4),
            new TraceSpec(Zone.DEN, Kind.FLOOR, Blocks.BLACKSTONE.defaultBlockState(), 2));
  }

  public List<TraceSpec> traces() {
    return switch (this) {
      case ZOMBIE -> Traces.ZOMBIE_TRACES;
      case SKELETON -> Traces.SKELETON_TRACES;
      case SPIDER -> Traces.SPIDER_TRACES;
      case CREEPER -> Traces.CREEPER_TRACES;
    };
  }

  public static boolean renewable(BlockState state) {
    return state.is(Blocks.COBWEB)
        || state.is(Blocks.WHITE_WOOL)
        || state.is(Blocks.TURTLE_EGG)
        || state.is(Blocks.BROWN_MUSHROOM);
  }

  public static @Nullable LairSpecies of(Entity entity) {
    return of(entity.getType());
  }

  public static @Nullable LairSpecies of(EntityType<?> type) {
    if (type == EntityType.ZOMBIE || type == EntityType.HUSK) {
      return ZOMBIE;
    }
    if (type == EntityType.SKELETON
        || type == EntityType.STRAY
        || type == EntityType.BOGGED
        || type == EntityType.PARCHED) {
      return SKELETON;
    }
    if (type == EntityType.SPIDER || type == EntityType.CAVE_SPIDER) {
      return SPIDER;
    }
    if (type == EntityType.CREEPER) {
      return CREEPER;
    }
    return null;
  }

  public static @Nullable LairSpecies byId(String id) {
    for (LairSpecies species : VALUES) {
      if (species.id().equals(id)) {
        return species;
      }
    }
    return null;
  }
}
