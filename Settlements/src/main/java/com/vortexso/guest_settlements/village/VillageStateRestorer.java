package com.vortexso.guest_settlements.village;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class VillageStateRestorer {
    private static final Identifier NONE =
            Identifier.fromNamespaceAndPath(
                    "minecraft",
                    "none"
            );

    /*
     * Sort order used whenever a villager has to change state or be
     * removed. Unnamed, zero-XP villagers are considered least
     * attached and are picked first.
     */
    private static final Comparator<Villager> LEAST_ATTACHED_FIRST =
            Comparator
                    .comparing(Villager::hasCustomName)
                    .thenComparing(villager ->
                            villager.getVillagerXp() > 0)
                    .thenComparing(villager ->
                            villager.getUUID().toString());

    private VillageStateRestorer() {
    }

    public static void restore(
            ServerLevel level,
            BoundingBox structureBox,
            VillageState state
    ) {
        VillagePopulation target =
                state.villagePopulation();

        Registry<VillagerProfession> professionRegistry =
                level.registryAccess()
                        .lookupOrThrow(
                                Registries.VILLAGER_PROFESSION
                        );

        List<Villager> villagers =
                new ArrayList<>(
                        VillagePopulationScanner.findVillagers(
                                level,
                                structureBox
                        )
                );

        int targetChildren =
                target.children();

        int targetAdults =
                target.adults();

        /*
         * First make the child/adult split match while preserving
         * the total number of villagers.
         */
        adjustAgeDistribution(
                villagers,
                targetChildren
        );

        List<Villager> children =
                babies(villagers);

        List<Villager> adults =
                grownups(villagers);

        /*
         * Trim excess population to match the aggregate state.
         */
        removeVillagers(
                adults,
                Math.max(
                        0,
                        adults.size() - targetAdults
                )
        );

        removeVillagers(
                children,
                Math.max(
                        0,
                        children.size() - targetChildren
                )
        );

        /*
         * Drop discarded villagers from the local view instead of
         * re-scanning the level. A second scan could also pick up
         * villagers that wandered in between the two passes.
         */
        children.removeIf(Villager::isRemoved);
        adults.removeIf(Villager::isRemoved);

        /*
         * Spawn missing villagers.
         */
        int missingChildren =
                Math.max(
                        0,
                        targetChildren - children.size()
                );

        for (int i = 0; i < missingChildren; i++) {
            Villager villager =
                    spawnVillager(
                            level,
                            structureBox,
                            true,
                            children.size()
                    );

            if (villager != null) {
                children.add(villager);
            }
        }

        int missingAdults =
                Math.max(
                        0,
                        targetAdults - adults.size()
                );

        for (int i = 0; i < missingAdults; i++) {
            Villager villager =
                    spawnVillager(
                            level,
                            structureBox,
                            false,
                            adults.size()
                    );

            if (villager != null) {
                adults.add(villager);
            }
        }

        /*
         * Assign the target profession distribution while preserving
         * existing matching professions whenever possible.
         */
        assignProfessions(
                adults,
                target.professions(),
                professionRegistry
        );
    }

    private static List<Villager> babies(
            List<Villager> villagers
    ) {
        return splitByAge(villagers, true);
    }

    private static List<Villager> grownups(
            List<Villager> villagers
    ) {
        return splitByAge(villagers, false);
    }

    private static List<Villager> splitByAge(
            List<Villager> villagers,
            boolean wantBabies
    ) {
        List<Villager> result = new ArrayList<>();

        for (Villager villager : villagers) {
            if (villager.isBaby() == wantBabies) {
                result.add(villager);
            }
        }

        return result;
    }

    private static void adjustAgeDistribution(
            List<Villager> villagers,
            int targetChildren
    ) {
        List<Villager> children =
                babies(villagers);

        List<Villager> adults =
                grownups(villagers);

        if (children.size() > targetChildren) {
            /*
             * Convert the least attached children into adults rather
             * than an arbitrary slice of the scanner output.
             */
            children.sort(LEAST_ATTACHED_FIRST);

            int convert =
                    children.size() - targetChildren;

            for (int i = 0; i < convert; i++) {
                children.get(i).setBaby(false);
            }

            return;
        }

        if (children.size() < targetChildren) {
            /*
             * Convert the least attached adults into children.
             */
            adults.sort(LEAST_ATTACHED_FIRST);

            int convert =
                    Math.min(
                            targetChildren - children.size(),
                            adults.size()
                    );

            for (int i = 0; i < convert; i++) {
                adults.get(i).setBaby(true);
            }
        }
    }

    private static void removeVillagers(
            List<Villager> villagers,
            int amount
    ) {
        if (amount <= 0) {
            return;
        }

        villagers.sort(LEAST_ATTACHED_FIRST);

        for (int i = 0;
             i < amount && i < villagers.size();
             i++) {

            villagers.get(i).discard();
        }
    }

    private static Villager spawnVillager(
            ServerLevel level,
            BoundingBox structureBox,
            boolean baby,
            int index
    ) {
        Villager villager =
                EntityType.VILLAGER.create(
                        level,
                        EntitySpawnReason.COMMAND
                );

        if (villager == null) {
            return null;
        }

        int x =
                structureBox.getCenter().getX();

        int z =
                structureBox.getCenter().getZ();

        int y =
                level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        x,
                        z
                );

        int offsetX =
                (index % 5) - 2;

        int offsetZ =
                (index / 5) % 5 - 2;

        villager.setPos(
                x + offsetX + 0.5,
                y,
                z + offsetZ + 0.5
        );

        villager.setBaby(baby);

        return level.addFreshEntity(villager)
                ? villager
                : null;
    }

    private static void assignProfessions(
            List<Villager> adults,
            Map<Identifier, Integer> target,
            Registry<VillagerProfession> registry
    ) {
        Map<Identifier, Integer> remaining =
                new HashMap<>(target);

        List<Villager> unmatched =
                new ArrayList<>();

        /*
         * Preserve existing profession assignments whenever the
         * target still contains that profession.
         */
        for (Villager villager : adults) {
            Identifier current =
                    villager.getVillagerData()
                            .profession()
                            .unwrapKey()
                            .map(ResourceKey::identifier)
                            .orElse(null);

            int needed =
                    current == null
                            ? 0
                            : remaining.getOrDefault(
                            current,
                            0
                    );

            if (needed > 0) {
                remaining.put(
                        current,
                        needed - 1
                );
            } else {
                unmatched.add(villager);
            }
        }

        List<Map.Entry<Identifier, Integer>> entries =
                remaining.entrySet()
                        .stream()
                        .filter(entry ->
                                entry.getValue() > 0
                        )
                        .sorted(Map.Entry.comparingByKey())
                        .toList();

        int villagerIndex = 0;

        for (Map.Entry<Identifier, Integer> entry :
                entries) {

            Holder<VillagerProfession> profession =
                    registry.get(entry.getKey())
                            .orElse(null);

            if (profession == null) {
                continue;
            }

            for (int i = 0;
                 i < entry.getValue()
                         && villagerIndex < unmatched.size();
                 i++) {

                applyProfession(
                        unmatched.get(villagerIndex++),
                        profession
                );
            }
        }

        /*
         * Any unmatched villagers left over can only happen when the
         * state refers to a profession which no longer exists.
         * They become unemployed instead.
         */
        Holder<VillagerProfession> none =
                registry.get(NONE)
                        .orElse(null);

        if (none == null) {
            return;
        }

        while (villagerIndex < unmatched.size()) {
            applyProfession(
                    unmatched.get(villagerIndex++),
                    none
            );
        }
    }

    /*
     * Assigns a profession and resets the villager's experience so
     * the derived level matches the new profession. Without this a
     * reassigned master farmer would become a "master librarian"
     * with no offers.
     */
    private static void applyProfession(
            Villager villager,
            Holder<VillagerProfession> profession
    ) {
        VillagerData data =
                villager.getVillagerData()
                        .withProfession(profession);

        villager.setVillagerData(data);
        villager.setVillagerXp(0);
    }
}