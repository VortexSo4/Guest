package com.vortexso.guest_settlements.village;

import com.vortexso.guest_core.api.GuestHash;

/**
 * Pure aggregate village simulation.
 * Does not access Minecraft world state.
 */
public final class VillageSimulator {
    private static final long EVENT_BIRTH = 0x42A11L;
    private static final long EVENT_MATURATION = 0x6D47L;
    private static final long EVENT_DEATH = 0xD34DL;

    private VillageSimulator() {
    }

    public static VillageState initialize(VillageRecord record) {
        return new VillageState(
                record.id(),
                record.origin(),
                record.initializedDay(),
                record.initialPopulation(),
                record.initialChildren(),
                record.initialHousingCapacity(),
                record.initialFoodReserve()
        );
    }

    public static VillageState simulateDay(
            VillageState state,
            VillageDayInput input,
            long worldSeed,
            VillageSimulationParameters parameters
    ) {
        long nextDay = state.day() + 1;

        /*
         * Migration currently concerns adults only.
         */
        int adultsBefore = state.adults();
        int outgoing = Math.min(input.outgoingVillagers(), adultsBefore);

        int adultsAfterMigration =
                adultsBefore
                        - outgoing
                        + input.incomingVillagers();

        int populationAfterMigration =
                state.children() + adultsAfterMigration;

        /*
         * Food is produced before the village consumes it.
         */
        double production =
                VillageEconomy.foodProduction(
                        input,
                        parameters
                );

        double consumption =
                populationAfterMigration
                        * parameters.foodPerVillagerPerDay();

        double foodReserve =
                Math.max(
                        0.0,
                        state.foodReserve()
                                + production
                                - consumption
                );

        /*
         * Zombies may kill both adults and children.
         * Deaths are distributed proportionally between them.
         */
        double deathRate = effectiveRate(
                parameters.baseZombieDeathRatePerDay()
                        * input.zombiePressure(),
                worldSeed,
                state.id(),
                nextDay,
                EVENT_DEATH,
                parameters.activeVariation()
        );

        int deaths = deterministicCount(
                populationAfterMigration * deathRate,
                GuestHash.hash(
                        worldSeed,
                        state.id(),
                        nextDay,
                        EVENT_DEATH
                )
        );

        deaths = Math.min(deaths, populationAfterMigration);

        int childrenAfterDeaths =
                removeChildrenProportionally(
                        state.children(),
                        populationAfterMigration,
                        deaths,
                        GuestHash.hash(
                                worldSeed,
                                state.id(),
                                nextDay,
                                EVENT_DEATH ^ 0xBEEFL
                        )
                );

        int populationAfterDeaths =
                populationAfterMigration - deaths;

        int adultsAfterDeaths =
                populationAfterDeaths - childrenAfterDeaths;

        /*
         * A child has a probability of maturing each day based
         * on the configured average maturation time.
         */
        double maturationRate =
                1.0 / parameters.childMaturationDays();

        maturationRate = effectiveRate(
                maturationRate,
                worldSeed,
                state.id(),
                nextDay,
                EVENT_MATURATION,
                parameters.activeVariation()
        );

        int matured = deterministicCount(
                childrenAfterDeaths * maturationRate,
                GuestHash.hash(
                        worldSeed,
                        state.id(),
                        nextDay,
                        EVENT_MATURATION
                )
        );

        matured = Math.min(matured, childrenAfterDeaths);

        int childrenAfterMaturation =
                childrenAfterDeaths - matured;

        int adultsAfterMaturation =
                adultsAfterDeaths + matured;

        /*
         * Breeding requires both available housing and enough food
         * for one eligible pair.
         */
        int freeHousing =
                Math.max(
                        0,
                        state.housingCapacity() - populationAfterDeaths
                );

        int eligiblePairs =
                Math.min(
                        adultsAfterMaturation / 2,
                        freeHousing
                );

        boolean breedingFoodAvailable =
                foodReserve
                        >= 2.0 * parameters.foodForBreedingVillager();

        int births = 0;

        if (breedingFoodAvailable && eligiblePairs > 0) {
            double birthRate = effectiveRate(
                    parameters.birthRatePerEligiblePairPerDay(),
                    worldSeed,
                    state.id(),
                    nextDay,
                    EVENT_BIRTH,
                    parameters.activeVariation()
            );

            births = deterministicCount(
                    eligiblePairs * birthRate,
                    GuestHash.hash(
                            worldSeed,
                            state.id(),
                            nextDay,
                            EVENT_BIRTH
                    )
            );

            births = Math.min(births, freeHousing);

            foodReserve = Math.max(
                    0.0,
                    foodReserve
                            - births
                            * 2.0
                            * parameters.foodForBreedingVillager()
            );
        }

        return new VillageState(
                state.id(),
                state.center(),
                nextDay,
                populationAfterDeaths + births,
                childrenAfterMaturation + births,
                state.housingCapacity(),
                foodReserve
        );
    }

    public static double foodProduction(
            VillageDayInput input,
            VillageSimulationParameters parameters
    ) {
        double potentialProduction =
                input.farmers()
                        * parameters.foodYieldPerFarmer();

        double fieldLimitedProduction =
                Math.min(
                        potentialProduction,
                        input.fieldCapacity()
                );

        return fieldLimitedProduction * input.fertility();
    }

    private static double effectiveRate(
            double baseRate,
            long seed,
            long villageId,
            long day,
            long event,
            double variation
    ) {
        if (variation == 0.0) {
            return Math.min(1.0, Math.max(0.0, baseRate));
        }

        double centered =
                GuestHash.unit(
                        GuestHash.hash(
                                seed,
                                villageId,
                                day,
                                event
                        )
                ) * 2.0 - 1.0;

        return Math.min(
                1.0,
                Math.max(
                        0.0,
                        baseRate * (1.0 + centered * variation)
                )
        );
    }

    private static int deterministicCount(
            double expected,
            long randomSeed
    ) {
        if (!(expected > 0.0)) {
            return 0;
        }

        long whole = (long) Math.floor(expected);
        double fractional = expected - whole;

        if (fractional > 0.0
                && GuestHash.unit(randomSeed) < fractional) {
            whole++;
        }

        return saturatingToInt(whole);
    }

    private static int removeChildrenProportionally(
            int children,
            int population,
            int deaths,
            long randomSeed
    ) {
        if (children == 0 || deaths == 0 || population == 0) {
            return children;
        }

        double expected =
                (double) children * deaths / population;

        int childDeaths = deterministicCount(
                expected,
                randomSeed
        );

        childDeaths =
                Math.min(childDeaths, children);

        return children - childDeaths;
    }

    private static int saturatingToInt(long value) {
        return (int) Math.min(
                Integer.MAX_VALUE,
                Math.max(0L, value)
        );
    }
}