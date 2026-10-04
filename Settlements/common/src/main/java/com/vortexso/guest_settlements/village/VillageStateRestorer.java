package com.vortexso.guest_settlements.village;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

public final class VillageStateRestorer {
  private static final Identifier NONE = Identifier.fromNamespaceAndPath("minecraft", "none");

  private static final Comparator<Villager> LEAST_ATTACHED_FIRST =
      Comparator.comparing(Villager::hasCustomName)
          .thenComparing(villager -> villager.getVillagerXp() > 0)
          .thenComparing(villager -> villager.getUUID().toString());

  public static final String INFECTED_TAG = "guest_settlements.infected";

  private VillageStateRestorer() {}

  public static void restore(
      ServerLevel level,
      BoundingBox structureBox,
      VillageState state,
      List<BlockPos> homes,
      Consumer<Villager> onSpawn) {
    VillagePopulation target = state.villagePopulation();

    Registry<VillagerProfession> professionRegistry =
        level.registryAccess().lookupOrThrow(Registries.VILLAGER_PROFESSION);

    List<Villager> villagers =
        new ArrayList<>(VillagePopulationScanner.findVillagers(level, structureBox));

    int targetChildren = target.children();

    int targetAdults = target.adults();

    adjustAgeDistribution(villagers, targetChildren);

    List<Villager> children = babies(villagers);

    List<Villager> adults = grownups(villagers);

    removeVillagers(adults, Math.max(0, adults.size() - targetAdults));

    removeVillagers(children, Math.max(0, children.size() - targetChildren));

    children.removeIf(Villager::isRemoved);
    adults.removeIf(Villager::isRemoved);

    int missingChildren = Math.max(0, targetChildren - children.size());

    for (int i = 0; i < missingChildren; i++) {
      Villager villager = spawnVillager(level, structureBox, homes, true, children.size());

      if (villager != null) {
        onSpawn.accept(villager);
        children.add(villager);
      }
    }

    int missingAdults = Math.max(0, targetAdults - adults.size());

    for (int i = 0; i < missingAdults; i++) {
      Villager villager = spawnVillager(level, structureBox, homes, false, adults.size());

      if (villager != null) {
        onSpawn.accept(villager);
        adults.add(villager);
      }
    }

    assignProfessions(adults, target.professions(), professionRegistry);
  }

  private static List<Villager> babies(List<Villager> villagers) {
    return splitByAge(villagers, true);
  }

  private static List<Villager> grownups(List<Villager> villagers) {
    return splitByAge(villagers, false);
  }

  private static List<Villager> splitByAge(List<Villager> villagers, boolean wantBabies) {
    List<Villager> result = new ArrayList<>();

    for (Villager villager : villagers) {
      if (villager.isBaby() == wantBabies) {
        result.add(villager);
      }
    }

    return result;
  }

  private static void adjustAgeDistribution(List<Villager> villagers, int targetChildren) {
    List<Villager> children = babies(villagers);

    List<Villager> adults = grownups(villagers);

    if (children.size() > targetChildren) {

      children.sort(LEAST_ATTACHED_FIRST);

      int convert = children.size() - targetChildren;

      for (int i = 0; i < convert; i++) {
        children.get(i).setBaby(false);
      }

      return;
    }

    if (children.size() < targetChildren) {

      adults.sort(LEAST_ATTACHED_FIRST);

      int convert = Math.min(targetChildren - children.size(), adults.size());

      for (int i = 0; i < convert; i++) {
        adults.get(i).setBaby(true);
      }
    }
  }

  private static void removeVillagers(List<Villager> villagers, int amount) {
    if (amount <= 0) {
      return;
    }

    villagers.sort(LEAST_ATTACHED_FIRST);

    for (int i = 0; i < amount && i < villagers.size(); i++) {

      villagers.get(i).discard();
    }
  }

  private static Villager spawnVillager(
      ServerLevel level, BoundingBox structureBox, List<BlockPos> homes, boolean baby, int index) {
    Villager villager = EntityType.VILLAGER.create(level, EntitySpawnReason.COMMAND);

    if (villager == null) {
      return null;
    }

    Vec3 pos = spawnPoint(level, structureBox, homes, index);
    villager.setPos(pos.x, pos.y, pos.z);

    villager.setBaby(baby);

    return level.addFreshEntity(villager) ? villager : null;
  }

  public static void restoreInfected(
      ServerLevel level, BoundingBox structureBox, int infected, List<BlockPos> homes) {
    int present =
        level
            .getEntitiesOfClass(
                ZombieVillager.class, VillagePopulationScanner.searchArea(structureBox))
            .size();
    for (int i = present; i < infected; i++) {
      ZombieVillager zombie = EntityType.ZOMBIE_VILLAGER.create(level, EntitySpawnReason.EVENT);
      if (zombie == null) {
        return;
      }
      Vec3 pos = spawnPoint(level, structureBox, homes, i);
      zombie.setPos(pos.x, pos.y, pos.z);
      zombie.finalizeSpawn(
          level,
          level.getCurrentDifficultyAt(zombie.blockPosition()),
          EntitySpawnReason.EVENT,
          null);
      zombie.setPersistenceRequired();
      zombie.addTag(INFECTED_TAG);
      level.addFreshEntity(zombie);
    }
  }

  private static Vec3 spawnPoint(
      ServerLevel level, BoundingBox structureBox, List<BlockPos> homes, int index) {
    if (!homes.isEmpty()) {
      return Vec3.atBottomCenterOf(homes.get(index % homes.size()).above());
    }
    int x = structureBox.getCenter().getX() + (index % 5) - 2;
    int z = structureBox.getCenter().getZ() + (index / 5) % 5 - 2;
    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    return new Vec3(x + 0.5, y, z + 0.5);
  }

  private static void assignProfessions(
      List<Villager> adults,
      Map<Identifier, Integer> target,
      Registry<VillagerProfession> registry) {
    Map<Identifier, Integer> remaining = new HashMap<>(target);

    List<Villager> unmatched = new ArrayList<>();

    for (Villager villager : adults) {
      Identifier current =
          villager
              .getVillagerData()
              .profession()
              .unwrapKey()
              .map(ResourceKey::identifier)
              .orElse(null);

      int needed = current == null ? 0 : remaining.getOrDefault(current, 0);

      if (needed > 0) {
        remaining.put(current, needed - 1);
      } else {
        unmatched.add(villager);
      }
    }

    List<Map.Entry<Identifier, Integer>> entries =
        remaining.entrySet().stream()
            .filter(entry -> entry.getValue() > 0)
            .sorted(Map.Entry.comparingByKey())
            .toList();

    int villagerIndex = 0;

    for (Map.Entry<Identifier, Integer> entry : entries) {

      Holder<VillagerProfession> profession = registry.get(entry.getKey()).orElse(null);

      if (profession == null) {
        continue;
      }

      for (int i = 0; i < entry.getValue() && villagerIndex < unmatched.size(); i++) {

        applyProfession(unmatched.get(villagerIndex++), profession);
      }
    }

    Holder<VillagerProfession> none = registry.get(NONE).orElse(null);

    if (none == null) {
      return;
    }

    while (villagerIndex < unmatched.size()) {
      applyProfession(unmatched.get(villagerIndex++), none);
    }
  }

  private static void applyProfession(Villager villager, Holder<VillagerProfession> profession) {
    VillagerData data = villager.getVillagerData().withProfession(profession);

    villager.setVillagerData(data);
    villager.setVillagerXp(0);
  }
}
