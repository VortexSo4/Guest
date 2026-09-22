package com.vortexso.guest_settlements.village;

public final class VillageEconomy {

    private VillageEconomy() {
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

    /**
     * Number of farmers that can fully utilize the available fields.
     */
    public static int effectiveFarmerCapacity(
            double fieldCapacity,
            VillageSimulationParameters parameters
    ) {
        if (fieldCapacity <= 0.0
                || parameters.foodYieldPerFarmer() <= 0.0) {
            return 0;
        }

        return (int) Math.ceil(
                fieldCapacity
                        / parameters.foodYieldPerFarmer()
        );
    }
}