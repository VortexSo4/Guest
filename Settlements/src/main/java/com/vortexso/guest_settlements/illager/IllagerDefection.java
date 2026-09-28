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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ConversionParams;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@EventBusSubscriber(modid = GuestSettlements.MODID)
public final class IllagerDefection {
  public static final String SETTLING_TAG = "guest_settlements.settling";
  public static final String FORMER_TAG = "guest_settlements.former_illager";

  private static final String LONE_TICKS = "guest_settlements.lone_ticks";
  private static final int INTERVAL = 100;
  private static final double ALONE = 32.0;
  private static final int VILLAGE_REACH = 64;
  private static final double WILLING = 0.5;
  private static final long EVENT_WILLING = 0xDEF3L;

  private IllagerDefection() {}

  @SubscribeEvent
  public static void onLevelTick(LevelTickEvent.Post event) {
    if (!(event.getLevel() instanceof ServerLevel level)
        || level.getGameTime() % INTERVAL != 0
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
      CompoundTag data = illager.getPersistentData();
      if (village == null) {
        if (illager.entityTags().contains(SETTLING_TAG)) {
          illager.removeTag(SETTLING_TAG);
          data.remove(LONE_TICKS);
        }
        continue;
      }
      illager.addTag(SETTLING_TAG);
      int lone = data.getIntOr(LONE_TICKS, 0) + INTERVAL;
      data.putInt(LONE_TICKS, lone);
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

  @SubscribeEvent
  public static void onTarget(LivingChangeTargetEvent event) {
    if (event.getEntity() instanceof AbstractIllager illager
        && illager.entityTags().contains(SETTLING_TAG)
        && (event.getNewAboutToBeSetTarget() instanceof AbstractVillager
            || event.getNewAboutToBeSetTarget() instanceof IronGolem)) {
      event.setCanceled(true);
    } else if (event.getEntity() instanceof IronGolem
        && event.getNewAboutToBeSetTarget() != null
        && event.getNewAboutToBeSetTarget().entityTags().contains(SETTLING_TAG)) {
      event.setCanceled(true);
    }
  }

  @SubscribeEvent
  public static void onJoin(EntityJoinLevelEvent event) {

    if (!event.loadedFromDisk()
        || !(event.getEntity() instanceof Villager villager)
        || !(event.getLevel() instanceof ServerLevel level)
        || !villager.entityTags().contains(FORMER_TAG)) {
      return;
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
  }
}
