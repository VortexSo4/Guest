package com.vortexso.guest_architects;

import com.vortexso.guest_architects.city.CityManager;
import com.vortexso.guest_architects.city.CityRelations;
import com.vortexso.guest_architects.city.Escalation;
import com.vortexso.guest_architects.city.LivingCities;
import com.vortexso.guest_architects.entity.Cure;
import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.platform.Events;
import java.util.function.IntConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.GameEventTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

public final class ArchitectsEvents {

  private static final int VANILLA_CONVERSION_TICKS = 300;

  private static final EquipmentSlot[] ARMOR_SLOTS = {
    EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
  };
  private static final Item[] ARCHITECT_ARMOR = {
    Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS
  };

  private ArchitectsEvents() {}

  static void init() {
    Events.CHUNK_LOAD.register(
        (level, chunk, newChunk) -> CityManager.get(level).queueChunk(chunk.getPos()));
    Events.LEVEL_TICK.register(level -> CityManager.get(level).tick());
    Events.BLOCK_BREAK.register(
        (level, player, pos, state) -> {
          if (player instanceof ServerPlayer serverPlayer) {
            CityRelations.onBlockBroken(level, serverPlayer, pos, state);
          }
          return true;
        });
    Events.ENTITY_JOIN.register(
        (entity, level, fromDisk) ->
            !(fromDisk
                && entity instanceof Display display
                && display.entityTags().contains(GuestArchitects.MODID + ".unmaking")));
    Events.USE_BLOCK.register(
        (player, level, hand, hit) ->
            isHeld(player) ? InteractionResult.FAIL : InteractionResult.PASS);
  }

  public static void onServerAboutToStart(MinecraftServer server) {
    LivingCities.bind(server.registryAccess());
  }

  public static void onPlace(
      ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state) {
    if (state.is(Blocks.COMPARATOR) || state.is(Blocks.REPEATER) || state.is(Blocks.OBSERVER)) {
      CityRelations.onAdvancedAction(level, player, pos);
    }
    CityRelations.onBlockPlaced(level, player, pos, state);
  }

  public static void onEnchant(Player player) {
    if (player.level() instanceof ServerLevel level) {
      CityRelations.onAdvancedAction(level, player, player.blockPosition());
    }
  }

  public static boolean cancelGameEvent(
      ServerLevel level, Entity cause, Holder<GameEvent> gameEvent, Vec3 position) {
    if (!(cause instanceof ServerPlayer player)) {
      return false;
    }
    CityManager manager = CityManager.get(level);
    if (manager.isSilenced(player)) {
      return true;
    }
    if (gameEvent.is(GameEvent.SHRIEK)) {
      CityRelations.onShriek(level, player, BlockPos.containing(position));
    }
    if (gameEvent.is(GameEventTags.VIBRATIONS)
        && !(player.isSteppingCarefully()
            && gameEvent.is(GameEventTags.IGNORE_VIBRATIONS_SNEAKING))) {
      manager.noiseBy(player, level.getGameTime());
    }
    return false;
  }

  public static boolean isHeld(Player player) {
    return Escalation.isHeld(player);
  }

  public static InteractionResult interactEntity(Player player, Entity target, ItemStack stack) {
    if (isHeld(player)) {
      return InteractionResult.FAIL;
    }
    return Cure.interact(player, target, stack);
  }

  public static void onEntityTick(Entity entity) {
    if (entity instanceof Mob mob && Cure.isCuring(mob)) {
      Cure.tick(mob);
    }
  }

  public static boolean delayConversion(
      LivingEntity entity, EntityType<?> outcome, IntConsumer conversionTimer) {
    if (outcome != EntityType.ZOMBIFIED_PIGLIN && outcome != EntityType.ZOGLIN) {
      return false;
    }
    if (!(entity.level() instanceof ServerLevel level)) {
      return false;
    }
    int extra = ArchitectsConfig.PIGLIN_ZOMBIFICATION_SECONDS.get() * 20 - VANILLA_CONVERSION_TICKS;
    if (extra <= 0) {
      return false;
    }
    long now = level.getGameTime();
    long elapsed = now - GuestArchitects.CONVERSION_MARK.get(entity);
    if (elapsed >= extra && elapsed <= extra + 2) {
      GuestArchitects.CONVERSION_MARK.set(entity, Long.MIN_VALUE / 2);
      return false;
    }
    GuestArchitects.CONVERSION_MARK.set(entity, now);
    conversionTimer.accept(VANILLA_CONVERSION_TICKS + 1 - extra);
    return true;
  }

  public static void onFinalizeSpawn(Mob mob, ServerLevel level, EntitySpawnReason spawnReason) {
    if (mob.getType() != EntityType.ZOMBIE
        || spawnReason != EntitySpawnReason.NATURAL
        || mob.isBaby()) {
      return;
    }
    long h =
        GuestHash.hash(
            level.getSeed(),
            BlockPos.containing(mob.getX(), mob.getY(), mob.getZ()).asLong(),
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
