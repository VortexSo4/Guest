package com.vortexso.guest_settlements.village;

import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public record VillagePopulation(
        int children,
        Map<Identifier, Integer> professions
) {
    public VillagePopulation {
        if (children < 0) {
            throw new IllegalArgumentException("children must be >= 0");
        }

        Objects.requireNonNull(professions, "professions");

        Map<Identifier, Integer> normalized = new HashMap<>();

        for (Map.Entry<Identifier, Integer> entry : professions.entrySet()) {
            Identifier profession = Objects.requireNonNull(
                    entry.getKey(),
                    "profession"
            );

            Integer amount = Objects.requireNonNull(
                    entry.getValue(),
                    "profession amount"
            );

            if (amount < 0) {
                throw new IllegalArgumentException(
                        "profession amount must be >= 0"
                );
            }

            if (amount > 0) {
                normalized.put(profession, amount);
            }
        }

        professions = Map.copyOf(normalized);
    }

    public int adults() {
        long total = 0;

        for (int amount : professions.values()) {
            total += amount;
        }

        if (total > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "adult population exceeds Integer.MAX_VALUE"
            );
        }

        return (int) total;
    }

    public int population() {
        return adults() + children();
    }

    public int professionCount(Identifier profession) {
        return professions.getOrDefault(profession, 0);
    }
}