package com.vortexso.guest_settlements.village;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

public final class VillagePopulationScanner {
  private static final double VILLAGER_SEARCH_MARGIN = 16.0;

  private VillagePopulationScanner() {}

  public static VillagePopulation scan(ServerLevel level, BoundingBox structureBox) {
    Registry<VillagerProfession> professionRegistry =
        level.registryAccess().lookupOrThrow(Registries.VILLAGER_PROFESSION);

    Map<Identifier, Integer> professions = new HashMap<>();

    int children = 0;

    for (Villager villager : findVillagers(level, structureBox)) {

      if (villager.isBaby()) {
        children++;
        continue;
      }

      Identifier profession =
          professionRegistry.getKey(villager.getVillagerData().profession().value());

      if (profession == null) {
        continue;
      }

      professions.merge(profession, 1, Integer::sum);
    }

    return new VillagePopulation(children, professions);
  }

  public static List<Villager> findVillagers(ServerLevel level, BoundingBox structureBox) {
    AABB area =
        new AABB(
                structureBox.minX(),
                structureBox.minY(),
                structureBox.minZ(),
                structureBox.maxX() + 1.0,
                structureBox.maxY() + 1.0,
                structureBox.maxZ() + 1.0)
            .inflate(VILLAGER_SEARCH_MARGIN);

    return level.getEntitiesOfClass(Villager.class, area);
  }
}
