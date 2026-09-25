package com.vortexso.guest_hands;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server config (synced to clients, so client-side grapple motion and sleep muffling read the same
 * values the server validates against).
 */
public final class HandsConfig {
  public static final ModConfigSpec SPEC;

  public static final ModConfigSpec.BooleanValue GRAPPLE_ENABLED;
  public static final ModConfigSpec.DoubleValue GRAPPLE_REACH;

  /**
   * Blocks per tick. Pillaring (jump + place) gains about one block per ~12 ticks of jump arc, so
   * ~0.08 b/t; the climb stays just below it so grappling never beats a pillar on speed.
   */
  public static final ModConfigSpec.DoubleValue CLIMB_SPEED;

  public static final ModConfigSpec.DoubleValue DESCEND_SPEED;
  public static final ModConfigSpec.DoubleValue SIDE_SPEED;

  /** Vertical deceleration while catching a fall; must exceed gravity (0.08 b/t²) to ever stop. */
  public static final ModConfigSpec.DoubleValue BRAKE_DECELERATION;

  public static final ModConfigSpec.DoubleValue DURABILITY_PER_BLOCK;

  public static final ModConfigSpec.BooleanValue CRAFTING_ENABLED;
  public static final ModConfigSpec.IntValue MAX_BATCH_CRAFTS;
  public static final ModConfigSpec.BooleanValue FURNACE_ENABLED;
  public static final ModConfigSpec.BooleanValue ANVIL_ENABLED;
  public static final ModConfigSpec.BooleanValue ENCHANTING_ENABLED;
  public static final ModConfigSpec.BooleanValue BREWING_ENABLED;
  public static final ModConfigSpec.IntValue DISPLAY_RADIUS;

  public static final ModConfigSpec.BooleanValue SLEEP_ENABLED;
  public static final ModConfigSpec.IntValue NIGHT_PASS_SECONDS;
  public static final ModConfigSpec.DoubleValue MUFFLED_VOLUME;

  static {
    ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

    builder.push("grapple");
    GRAPPLE_ENABLED = builder.define("grappleEnabled", true);
    GRAPPLE_REACH =
        builder
            .comment(
                "Max gap in blocks between the player's body and the block the pickaxe catches.")
            .defineInRange("grappleReach", 1.0, 0.1, 3.0);
    CLIMB_SPEED = builder.defineInRange("climbSpeed", 0.07, 0.01, 0.5);
    DESCEND_SPEED = builder.defineInRange("descendSpeed", 0.15, 0.01, 1.0);
    SIDE_SPEED = builder.defineInRange("sideSpeed", 0.08, 0.01, 0.5);
    BRAKE_DECELERATION = builder.defineInRange("brakeDeceleration", 0.15, 0.09, 1.0);
    DURABILITY_PER_BLOCK =
        builder
            .comment("Durability per block of height/distance carried by the pickaxe.")
            .defineInRange("durabilityPerBlock", 1.0, 0.0, 16.0);
    builder.pop();

    builder.push("workstations");
    CRAFTING_ENABLED = builder.define("craftingEnabled", true);
    MAX_BATCH_CRAFTS = builder.defineInRange("maxBatchCrafts", 64, 1, 576);
    FURNACE_ENABLED = builder.define("furnaceEnabled", true);
    ANVIL_ENABLED = builder.define("anvilEnabled", true);
    ENCHANTING_ENABLED = builder.define("enchantingEnabled", true);
    BREWING_ENABLED = builder.define("brewingEnabled", true);
    DISPLAY_RADIUS =
        builder
            .comment("Furnaces/brewing stands within this many blocks of a player show contents.")
            .defineInRange("displayRadius", 16, 4, 64);
    builder.pop();

    builder.push("sleep");
    SLEEP_ENABLED = builder.define("slowSleepEnabled", true);
    NIGHT_PASS_SECONDS = builder.defineInRange("nightPassSeconds", 8, 1, 60);
    MUFFLED_VOLUME = builder.defineInRange("muffledVolume", 0.25, 0.0, 1.0);
    builder.pop();

    SPEC = builder.build();
  }

  private HandsConfig() {}
}
