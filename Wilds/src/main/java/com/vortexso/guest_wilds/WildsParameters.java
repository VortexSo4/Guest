package com.vortexso.guest_wilds;

/**
 * Aggregate tuning values in one place. Each default names its basis; values without a vanilla
 * counterpart are marked as tunable estimates.
 */
public record WildsParameters(
    // --- surface wear (units: one adult-sized passage over a column) ---
    // Vanilla grass re-spreads onto bare dirt within minutes, but compacted trail soil should
    // outlive a single visit: a route walked once a day keeps a trampled look (tunable).
    double wearHalfLifeDays,
    double trampleWear,
    double wornWear,
    double pathWear,
    // A stage reverts only below this fraction of its threshold, so paths don't flicker.
    double revertFraction,
    double routeMeanderBlocks,
    double routeMeanderWavelength,

    // --- lairs ---
    // Vanilla monster cap: 70 per 17x17 chunks around a player ~= 0.24 monsters per chunk, i.e.
    // ~3.9 per 64x64 lair cell. Roughly half the cells hold a usable cave, so a full lair holds ~8.
    double monstersPerCell,
    double lairCapacity,
    // No vanilla analogue (monsters refill instantly up to the cap); chosen so a cleared lair
    // recovers within about one 8-day week.
    double zombieGrowthPerDay,
    double spiderGrowthPerDay,
    double creeperGrowthPerDay,
    // PLACEHOLDER: skeleton origin is intentionally unresolved (spec "Unknowns").
    double skeletonGrowthPerDay,
    double lairMortalityPerDay,
    // Lotka-Volterra competition coefficient; < 1 lets well-matched species coexist.
    double competition,
    double lairOccupiedChance,
    // Two moon cycles of coexistence before riders appear.
    double jockeyStableDays,
    // One season in a swamp turns skeletons into bogged.
    double boggedResidenceDays,
    int materializeRadius,
    int materializePerCheck,

    // --- herds ---
    // Player breeding allows a birth per pair every 5 min (cooldown) with 20 min growth; wild
    // herds without a breeder are orders of magnitude slower (tunable).
    double herdBirthPerDay,
    double herdMortalityPerDay,
    double predationPerDayAtBaseline,
    // Sheep eat on average once per 1000 ticks (EatBlockGoal) = 24 blocks/day; a 64x64 pasture
    // has ~1200 forage blocks -> ~0.02 of a pasture per animal-day.
    double grazePerAnimalDay,
    // Forage (not the grass block itself, which vanilla re-spreads in minutes) takes ~3 weeks to
    // recover; with it a large herd exhausts a pasture while a small one can settle (tunable).
    double vegetationRegrowthPerDay,
    double migrateBelowVegetation,
    double predatorAvoidance,
    double herdCapacity,
    int herdJoinRadius,

    // --- fish ---
    // Vanilla water_ambient cap is 20 per player; a shoal is a few dozen fish (tunable).
    double oceanShoal,
    double riverShoal,
    double lakeShoal,
    double shoalRegrowthPerDay,
    double multiCatchRatio,
    double depletedRatio,
    // A vanilla bite comes after 5-30 s; each further minute of patience may add a fish.
    int waitTicksPerExtraFish,
    double rareFindChance,
    double visibleFishRatio,
    int maxVisibleFish) {

  public static final WildsParameters DEFAULT =
      new WildsParameters(
          4.0, 8.0, 32.0, 96.0, 0.5, 2.0, 20.0, // wear
          3.9, 8.0, 0.35, 0.5, 0.25, 0.15, 0.03, 0.7, 0.7, 16.0, 32.0, 48, 3, // lairs
          0.05, 0.01, 0.03, 0.02, 0.05, 0.3, 0.3, 12.0, 48, // herds
          40.0, 24.0, 12.0, 0.3, 0.6, 0.15, 1200, 0.04, 0.5, 4); // fish
}
