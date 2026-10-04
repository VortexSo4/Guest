package com.vortexso.guest_settlements.illager;

import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.history.GuestHistory;
import com.vortexso.guest_settlements.GuestSettlements;
import com.vortexso.guest_settlements.SettlementsConfig;
import com.vortexso.guest_settlements.memory.VillageMemory;
import com.vortexso.guest_settlements.village.VillageNode;
import com.vortexso.guest_settlements.village.VillageState;
import com.vortexso.guest_settlements.village.VillageWorldManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ConversionParams;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.illager.AbstractIllager;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.entity.EntityTypeTest;
import org.jspecify.annotations.Nullable;

public final class IllagerDefection {
  public static final String SETTLING_TAG = "guest_settlements.settling";
  public static final String FORMER_TAG = "guest_settlements.former_illager";

  private static final int INTERVAL = 100;
  private static final double ALONE = 32.0;
  private static final int VILLAGE_REACH = 64;
  private static final double WILLING = 0.5;
  private static final long EVENT_WILLING = 0xDEF3L;

  private IllagerDefection() {}

  public static void onLevelTick(ServerLevel level) {
    if (level.getGameTime() % INTERVAL != 0
        || !SettlementsConfig.enabled(SettlementsConfig.DEFECTION)) {
      return;
    }
    VillageWorldManager manager = VillageWorldManager.get(level);
    for (AbstractIllager illager :
        level.getEntities(
            EntityTypeTest.forClass(AbstractIllager.class),
            illager ->
                (illager instanceof Pillager || illager instanceof Vindicator)
                    && illager.isAlive())) {
      VillageNode village = candidateVillage(level, manager, illager);
      if (village == null) {
        if (illager.entityTags().contains(SETTLING_TAG)) {
          illager.removeTag(SETTLING_TAG);
          GuestSettlements.LONE_TICKS.set(illager, 0);
        }
        continue;
      }
      illager.addTag(SETTLING_TAG);
      int lone = GuestSettlements.LONE_TICKS.get(illager) + INTERVAL;
      GuestSettlements.LONE_TICKS.set(illager, lone);
      if (illager.getTarget() instanceof AbstractVillager
          || illager.getTarget() instanceof IronGolem) {
        illager.setTarget(null);
      }
      if (illager.getNavigation().isDone()) {
        BlockPos toward = village.bell() != null ? village.bell() : village.center();
        illager
            .getNavigation()
            .moveTo(toward.getX() + 0.5, toward.getY(), toward.getZ() + 0.5, 0.5);
      }
      if (lone >= SettlementsConfig.value(SettlementsConfig.DEFECTION_TICKS)) {
        defect(level, illager, village);
      }
    }
  }

  private static VillageNode candidateVillage(
      ServerLevel level, VillageWorldManager manager, AbstractIllager illager) {
    if (illager.hasActiveRaid()
        || illager.getTarget() instanceof net.minecraft.world.entity.player.Player
        || illager.entityTags().contains(IllagerCamps.MATERIALIZED_TAG)
        || illager.entityTags().contains(IllagerRhythm.TENT_TAG)
        || IllagerCamps.campAt(level, illager.blockPosition()) != null
        || GuestHash.unit(
                GuestHash.hash(
                    level.getSeed(), illager.getUUID().getMostSignificantBits(), EVENT_WILLING))
            >= WILLING
        || !level
            .getEntitiesOfClass(
                Raider.class, illager.getBoundingBox().inflate(ALONE), other -> other != illager)
            .isEmpty()) {
      return null;
    }
    VillageNode village = manager.nearest(illager.blockPosition());
    if (village == null
        || village.center().distSqr(illager.blockPosition())
            > (double) VILLAGE_REACH * VILLAGE_REACH) {
      return null;
    }
    VillageState state = village.state();
    return state == null || state.fallen() || state.population() == 0 ? null : village;
  }

  private static void defect(ServerLevel level, AbstractIllager illager, VillageNode village) {
    Villager villager =
        illager.convertTo(
            EntityType.VILLAGER,
            ConversionParams.single(illager, false, false),
            converted -> {
              converted.setVillagerData(
                  converted
                      .getVillagerData()
                      .withType(
                          level.registryAccess(),
                          VillagerType.byBiome(level.getBiome(illager.blockPosition())))
                      .withProfession(level.registryAccess(), VillagerProfession.NONE));
              converted.addTag(FORMER_TAG);
              converted.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
              converted.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
            });
    if (villager != null) {
      VillageMemory.recordIds(
          level, village.center(), VillageMemory.ILLAGER_JOINED, null, villager.getUUID(), 1);
      GuestSettlements.LOGGER.debug(
          "Illager settled in the village at {} as {}", village.center(), villager.getUUID());
    }
  }

  public static boolean allowTarget(LivingEntity mob, @Nullable LivingEntity target) {
    if (mob instanceof AbstractIllager illager
        && illager.entityTags().contains(SETTLING_TAG)
        && (target instanceof AbstractVillager || target instanceof IronGolem)) {
      return false;
    }
    return !(mob instanceof IronGolem
        && target != null
        && target.entityTags().contains(SETTLING_TAG));
  }

  public static boolean onJoin(Entity entity, ServerLevel level, boolean fromDisk) {
    if (!fromDisk
        || !(entity instanceof Villager villager)
        || !villager.entityTags().contains(FORMER_TAG)) {
      return true;
    }
    boolean remembered =
        !GuestHistory.get(level)
            .near(
                villager.blockPosition(),
                256,
                r ->
                    r.kind().equals(VillageMemory.ILLAGER_JOINED)
                        && r.subject().map(villager.getUUID()::equals).orElse(false))
            .isEmpty();
    if (!remembered) {
      villager.removeTag(FORMER_TAG);
      villager.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
    }
    return true;
  }
}
