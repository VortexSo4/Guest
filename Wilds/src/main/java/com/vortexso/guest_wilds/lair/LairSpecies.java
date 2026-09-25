package com.vortexso.guest_wilds.lair;

import com.vortexso.guest_wilds.WildsParameters;
import java.util.Locale;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** Species that can hold a lair. Husk/stray/bogged are forms of the same populations. */
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

  /**
   * Habitat fit by cave openness (0 tight tunnels .. 1 wide chamber): chambers give skeletons
   * firing lines, tunnels favour spiders, zombies win by numbers anywhere, creepers are weak
   * fighters that survive by keeping to the margins.
   */
  public double fit(double openness) {
    return switch (this) {
      case SKELETON -> 0.4 + 0.8 * openness;
      case SPIDER -> 1.2 - 0.8 * openness;
      case ZOMBIE -> 0.9;
      case CREEPER -> 0.6;
    };
  }

  public double growth(WildsParameters p) {
    return switch (this) {
      case ZOMBIE -> p.zombieGrowthPerDay();
      case SKELETON -> p.skeletonGrowthPerDay();
      case SPIDER -> p.spiderGrowthPerDay();
      case CREEPER -> p.creeperGrowthPerDay();
    };
  }

  /** Trace block laid on the floor (null: the trace is placed in the air instead). */
  public @Nullable BlockState floorTrace(int index) {
    return switch (this) {
      case ZOMBIE -> Blocks.ROOTED_DIRT.defaultBlockState(); // rotting bedding
      case SKELETON -> Blocks.BONE_BLOCK.defaultBlockState();
      case CREEPER ->
          index == 0 ? Blocks.AIR.defaultBlockState() : Blocks.BLACKSTONE.defaultBlockState();
      case SPIDER -> null;
    };
  }

  /** Trace placed standing on the floor. */
  public @Nullable BlockState airTrace(int index) {
    return switch (this) {
      case SPIDER -> Blocks.COBWEB.defaultBlockState(); // egg cocoons and trap passages
      case SKELETON -> index == 0 ? Blocks.SKELETON_SKULL.defaultBlockState() : null;
      case ZOMBIE -> index == 0 ? Blocks.BROWN_MUSHROOM.defaultBlockState() : null;
      case CREEPER -> null;
    };
  }

  public static @Nullable LairSpecies of(Entity entity) {
    EntityType<?> type = entity.getType();
    if (type == EntityType.ZOMBIE || type == EntityType.HUSK) {
      return ZOMBIE;
    }
    if (type == EntityType.SKELETON || type == EntityType.STRAY || type == EntityType.BOGGED) {
      return SKELETON;
    }
    if (type == EntityType.SPIDER) {
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
