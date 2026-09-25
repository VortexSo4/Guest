package com.vortexso.guest_architects;

import com.vortexso.guest_architects.city.CityManager;
import com.vortexso.guest_architects.city.CityRelations;
import com.vortexso.guest_core.api.GuestHash;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.GameEventTags;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.VanillaGameEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEnchantItemEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@EventBusSubscriber(modid = GuestArchitects.MODID)
public final class ArchitectsEvents {
  /** Vanilla piglin/hoglin Overworld conversion time ({@code AbstractPiglin.CONVERSION_TIME}). */
  private static final int VANILLA_CONVERSION_TICKS = 300;

  private static final String CONVERSION_MARK = GuestArchitects.MODID + ":conversion_delay";

  private static final EquipmentSlot[] ARMOR_SLOTS = {
    EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
  };
  private static final Item[] ARCHITECT_ARMOR = {
    Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS
  };

  private ArchitectsEvents() {}

  @SubscribeEvent
  public static void onChunkLoad(ChunkEvent.Load event) {
    if (event.getLevel() instanceof ServerLevel level) {
      CityManager.get(level).queueChunk(event.getChunk().getPos());
    }
  }

  @SubscribeEvent
  public static void onLevelTick(LevelTickEvent.Post event) {
    if (event.getLevel() instanceof ServerLevel level) {
      CityManager.get(level).tick();
    }
  }

  @SubscribeEvent
  public static void onBreak(BreakBlockEvent event) {
    if (event.getLevel() instanceof ServerLevel level
        && event.getPlayer() instanceof ServerPlayer player) {
      CityRelations.onBlockBroken(level, player, event.getPos(), event.getState());
    }
  }

  @SubscribeEvent
  public static void onPlace(BlockEvent.EntityPlaceEvent event) {
    if (!(event.getLevel() instanceof ServerLevel level)
        || !(event.getEntity() instanceof ServerPlayer player)) {
      return;
    }
    BlockState state = event.getPlacedBlock();
    if (state.is(Blocks.COMPARATOR) || state.is(Blocks.REPEATER) || state.is(Blocks.OBSERVER)) {
      CityRelations.onAdvancedAction(level, player, event.getPos());
    }
    CityRelations.onBlockPlaced(level, player, event.getPos(), state);
  }

  @SubscribeEvent
  public static void onEnchant(PlayerEnchantItemEvent event) {
    if (event.getEntity().level() instanceof ServerLevel level) {
      CityRelations.onAdvancedAction(level, event.getEntity(), event.getEntity().blockPosition());
    }
  }

  /** Hot path (every game event): bail out before anything but a cheap type check. */
  @SubscribeEvent
  public static void onGameEvent(VanillaGameEvent event) {
    if (!(event.getCause() instanceof ServerPlayer player)
        || !(event.getLevel() instanceof ServerLevel level)) {
      return;
    }
    Holder<GameEvent> gameEvent = event.getVanillaEvent();
    if (gameEvent.is(GameEvent.SHRIEK)) {
      CityRelations.onShriek(level, player, BlockPos.containing(event.getEventPosition()));
    }
    if (gameEvent.is(GameEventTags.VIBRATIONS)
        && !(player.isSteppingCarefully()
            && gameEvent.is(GameEventTags.IGNORE_VIBRATIONS_SNEAKING))) {
      CityManager.get(level).noiseBy(player, level.getGameTime());
    }
  }

  /**
   * Stretches the vanilla 15 s piglin/hoglin conversion without a mixin: the first time vanilla
   * wants to convert, the timer is wound back by the extra time. Conversion is allowed only when
   * the timer then ran without interruption; leaving the Overworld resets it and the wind-back
   * repeats.
   */
  @SubscribeEvent
  public static void onConversion(LivingConversionEvent.Pre event) {
    EntityType<?> outcome = event.getOutcome();
    if (outcome != EntityType.ZOMBIFIED_PIGLIN && outcome != EntityType.ZOGLIN) {
      return;
    }
    LivingEntity entity = event.getEntity();
    if (!(entity.level() instanceof ServerLevel level)) {
      return;
    }
    int extra = ArchitectsConfig.PIGLIN_ZOMBIFICATION_SECONDS.get() * 20 - VANILLA_CONVERSION_TICKS;
    if (extra <= 0) {
      return;
    }
    CompoundTag data = entity.getPersistentData();
    long now = level.getGameTime();
    long elapsed = now - data.getLongOr(CONVERSION_MARK, Long.MIN_VALUE / 2);
    if (elapsed >= extra && elapsed <= extra + 2) {
      data.remove(CONVERSION_MARK);
      return;
    }
    data.putLong(CONVERSION_MARK, now);
    event.setConversionTimer(VANILLA_CONVERSION_TICKS + 1 - extra);
    event.setCanceled(true);
  }

  /** Rare zombies still wearing worn, enchanted Architect armor. */
  @SubscribeEvent
  public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
    Mob mob = event.getEntity();
    if (mob.getType() != EntityType.ZOMBIE
        || event.getSpawnType() != EntitySpawnReason.NATURAL
        || mob.isBaby()) {
      return;
    }
    ServerLevel level = event.getLevel().getLevel();
    long h =
        GuestHash.hash(
            level.getSeed(),
            BlockPos.containing(event.getX(), event.getY(), event.getZ()).asLong(),
            level.getGameTime());
    if (GuestHash.unit(h) >= ArchitectsConfig.ARCHITECT_EQUIPMENT_ZOMBIE_CHANCE.get()) {
      return;
    }
    Holder<Enchantment> protection =
        level
            .registryAccess()
            .lookupOrThrow(Registries.ENCHANTMENT)
            .getOrThrow(Enchantments.PROTECTION);
    for (int i = 0; i < ARMOR_SLOTS.length; i++) {
      double u = GuestHash.unit(GuestHash.hash(h, i));
      // The chestplate always survives; other pieces are a coin flip.
      if (i != 1 && u < 0.5) {
        continue;
      }
      ItemStack stack = new ItemStack(ARCHITECT_ARMOR[i]);
      stack.enchant(protection, 1 + (int) (u * 4) % 4);
      stack.setDamageValue((int) (stack.getMaxDamage() * (0.5 + 0.4 * u)));
      mob.setItemSlot(ARMOR_SLOTS[i], stack);
    }
  }
}
