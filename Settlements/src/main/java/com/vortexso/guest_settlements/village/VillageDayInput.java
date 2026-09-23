package com.vortexso.guest_settlements.village;

/**
 * External inputs for one simulated day.
 * Contains no Minecraft world access.
 */
public record VillageDayInput(
        double fieldCapacity,
        double fertility,
        double zombiePressure,
        int incomingVillagers,
        int outgoingVillagers
) {
    public VillageDayInput {
        if (!Double.isFinite(fieldCapacity)
                || fieldCapacity < 0.0) {
            throw new IllegalArgumentException(
                    "fieldCapacity must be finite and >= 0"
            );
        }

        if (!Double.isFinite(fertility)
                || fertility < 0.0
                || fertility > 1.0) {
            throw new IllegalArgumentException(
                    "fertility must be in [0, 1]"
            );
        }

        if (!Double.isFinite(zombiePressure)
                || zombiePressure < 0.0) {
            throw new IllegalArgumentException(
                    "zombiePressure must be finite and >= 0"
            );
        }

        if (incomingVillagers < 0) {
            throw new IllegalArgumentException(
                    "incomingVillagers must be >= 0"
            );
        }

        if (outgoingVillagers < 0) {
            throw new IllegalArgumentException(
                    "outgoingVillagers must be >= 0"
            );
        }
    }
}