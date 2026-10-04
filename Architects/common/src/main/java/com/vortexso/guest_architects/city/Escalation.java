package com.vortexso.guest_architects.city;

import com.vortexso.guest_architects.ArchitectsConfig;
import com.vortexso.guest_architects.GuestArchitects;
import com.vortexso.guest_architects.entity.Architect;
import com.vortexso.guest_architects.entity.Displays;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class Escalation {
  public static final Identifier HELD =
      Identifier.fromNamespaceAndPath(GuestArchitects.MODID, "held");
  static final String UNMAKING_TAG = GuestArchitects.MODID + ".unmaking";

  private static final List<Holder<Attribute>> HELD_ATTRIBUTES =
      List.of(
          Attributes.MOVEMENT_SPEED,
          Attributes.JUMP_STRENGTH,
          Attributes.BLOCK_INTERACTION_RANGE,
          Attributes.ENTITY_INTERACTION_RANGE,
          Attributes.BLOCK_BREAK_SPEED);

  private static final EquipmentSlot[] ARMOR = {
    EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
  };

  private static final double LIFT_IMPULSE = 0.06;

  private static final int LIFT_TICKS = 40;
  private static final int HOLD_TICKS = 140;
  private static final int ITEM_TICKS = 50;
  private static final int ITEM_BURN = 12;
  private static final int ITEM_GONE = 38;
  private static final int PUSH_DELAY = 20;
  private static final int PORTAL_FALLBACK = 60;

  public enum Kind {
    HOLD,

    UNMAKE,

    REMOVE,

    QUICK_REMOVE
  }

  final ServerPlayer player;
  final Kind kind;
  private final CitySite site;
  private final @Nullable Architect witness;
  private final BlockPos floor;
  private final List<Take> takes;
  private int ticks;
  private Display.@Nullable ItemDisplay shown;
  private ItemStack shownStack = ItemStack.EMPTY;
  private boolean finished;

  private record Take(@Nullable EquipmentSlot slot, int inventorySlot) {}

  Escalation(CitySite site, ServerPlayer player, @Nullable Architect witness, Kind kind) {
    this.site = site;
    this.player = player;
    this.witness = witness;
    this.kind = kind;
    this.floor = player.blockPosition();
    this.takes = kind == Kind.UNMAKE ? plan(player) : List.of();
    hold(kind != Kind.QUICK_REMOVE);
    if (kind != Kind.QUICK_REMOVE) {
      player.setDeltaMovement(0.0, LIFT_IMPULSE, 0.0);
      player.hurtMarked = true;
    }
    if (witness != null) {
      witness.watch(player, LIFT_TICKS + HOLD_TICKS + takes.size() * ITEM_TICKS, false);
      CityManager.glyphs(player.level(), witness.getEyePosition(), player.getEyePosition(), 16);
    }
  }

  public static boolean isHeld(net.minecraft.world.entity.player.Player player) {
    AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
    return speed != null && speed.hasModifier(HELD);
  }

  boolean tick(ServerLevel level) {
    if (finished) {
      return false;
    }
    if (player.isRemoved() || !player.isAlive() || player.level() != level) {
      release(false);
      return false;
    }
    ticks++;
    switch (kind) {
      case HOLD -> {
        if (ticks >= LIFT_TICKS + HOLD_TICKS) {
          release(true);
        }
      }
      case UNMAKE -> tickUnmake(level);
      case REMOVE -> {
        if (ticks == LIFT_TICKS) {
          openPortal(level, floor);
        } else if (ticks == LIFT_TICKS + PUSH_DELAY) {
          push();
        } else if (ticks >= LIFT_TICKS + PUSH_DELAY + PORTAL_FALLBACK) {
          sendAway(level);
        }
      }
      case QUICK_REMOVE -> {
        if (ticks == 1) {
          openPortal(level, player.blockPosition());
          push();
        } else if (ticks >= PORTAL_FALLBACK) {
          sendAway(level);
        }
      }
    }
    return !finished;
  }

  private void tickUnmake(ServerLevel level) {
    int t = ticks - LIFT_TICKS;
    if (t < 0) {
      return;
    }
    int step = t / ITEM_TICKS;
    int phase = t % ITEM_TICKS;
    if (step >= takes.size()) {
      release(true);
      return;
    }
    Take take = takes.get(step);
    if (phase == 0) {
      shownStack = take(take);
      if (!shownStack.isEmpty()) {
        Vec3 look = player.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0.0, look.z).normalize();
        Vec3 at = player.getEyePosition().add(horizontal.scale(1.4));
        shown = Displays.spawnItem(level, at, shownStack, 0.75F, UNMAKING_TAG, true);
        if (witness != null) {
          CityManager.glyphs(level, witness.getEyePosition(), at, 10);
        }
      }
    }
    if (shown == null) {
      return;
    }
    Vec3 at = shown.position();
    boolean burn = ArchitectsConfig.DESTROY_EQUIPMENT.get();
    if (phase == ITEM_BURN && burn) {
      Displays.animate(shown, 0.0F, (float) Math.PI * 2.0F, ITEM_GONE - ITEM_BURN);
      level.playSound(
          null, at.x, at.y, at.z, GuestArchitects.UNMAKE.get(), SoundSource.PLAYERS, 1.0F, 0.8F);
    }
    if (burn && phase > ITEM_BURN && phase < ITEM_GONE && phase % 3 == 0) {
      level.sendParticles(ParticleTypes.SMALL_FLAME, at.x, at.y, at.z, 3, 0.12, 0.12, 0.12, 0.01);
      level.sendParticles(ParticleTypes.SMOKE, at.x, at.y + 0.1, at.z, 2, 0.1, 0.1, 0.1, 0.01);
    }
    if (phase == ITEM_GONE) {
      if (burn) {
        level.sendParticles(
            new ItemParticleOption(ParticleTypes.ITEM, shownStack.getItem()),
            at.x,
            at.y,
            at.z,
            14,
            0.15,
            0.15,
            0.15,
            0.08);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y, at.z, 4, 0.1, 0.1, 0.1, 0.01);
      } else {
        ItemEntity dropped =
            new ItemEntity(
                level, floor.getX() + 0.5, floor.getY() + 0.2, floor.getZ() + 0.5, shownStack);
        level.addFreshEntity(dropped);
      }
      shown.discard();
      shown = null;
    }
  }

  private void hold(boolean weightless) {
    for (Holder<Attribute> attribute : HELD_ATTRIBUTES) {
      modify(attribute);
    }
    if (weightless) {
      modify(Attributes.GRAVITY);
    }
  }

  private void modify(Holder<Attribute> attribute) {
    AttributeInstance instance = player.getAttribute(attribute);
    if (instance != null && !instance.hasModifier(HELD)) {
      instance.addTransientModifier(
          new AttributeModifier(HELD, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }
  }

  private static void unhold(ServerPlayer player) {
    for (Holder<Attribute> attribute : HELD_ATTRIBUTES) {
      AttributeInstance instance = player.getAttribute(attribute);
      if (instance != null) {
        instance.removeModifier(HELD);
      }
    }
    AttributeInstance gravity = player.getAttribute(Attributes.GRAVITY);
    if (gravity != null) {
      gravity.removeModifier(HELD);
    }
  }

  void release(boolean setDown) {
    finished = true;
    unhold(player);
    if (shown != null) {
      shown.discard();
      shown = null;
    }
    if (setDown) {
      player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 60, 0, false, false, false));
    }
  }

  private static List<Take> plan(ServerPlayer player) {
    List<Take> takes = new ArrayList<>();
    for (EquipmentSlot slot : ARMOR) {
      if (!player.getItemBySlot(slot).isEmpty()) {
        takes.add(new Take(slot, -1));
      }
    }
    Inventory inventory = player.getInventory();
    int weapon = best(inventory, Escalation::isWeapon, Escalation::attackDamage, -1);
    if (weapon >= 0) {
      takes.add(new Take(null, weapon));
    }
    long pickaxes =
        inventory.getNonEquipmentItems().stream().filter(s -> s.is(ItemTags.PICKAXES)).count();
    int tool =
        best(
            inventory,
            s -> isTool(s) && !(s.is(ItemTags.PICKAXES) && pickaxes <= 1),
            s -> s.getMaxDamage(),
            weapon);
    if (tool >= 0) {
      takes.add(new Take(null, tool));
    }
    return takes;
  }

  private static int best(
      Inventory inventory, Predicate<ItemStack> kind, ToDoubleFunction<ItemStack> value, int skip) {
    int best = -1;
    double bestValue = Double.NEGATIVE_INFINITY;
    for (int i = 0; i < inventory.getNonEquipmentItems().size(); i++) {
      ItemStack stack = inventory.getItem(i);
      if (i != skip
          && !stack.isEmpty()
          && kind.test(stack)
          && value.applyAsDouble(stack) > bestValue) {
        best = i;
        bestValue = value.applyAsDouble(stack);
      }
    }
    return best;
  }

  private static boolean isWeapon(ItemStack stack) {
    return stack.is(ItemTags.SWORDS)
        || stack.is(ItemTags.AXES)
        || stack.is(ItemTags.SPEARS)
        || stack.is(Items.MACE)
        || stack.is(Items.TRIDENT);
  }

  private static boolean isTool(ItemStack stack) {
    return stack.is(ItemTags.PICKAXES)
        || stack.is(ItemTags.AXES)
        || stack.is(ItemTags.SHOVELS)
        || stack.is(ItemTags.HOES)
        || stack.is(Items.SHEARS)
        || stack.is(Items.BRUSH);
  }

  private static double attackDamage(ItemStack stack) {
    return stack
        .getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
        .compute(Attributes.ATTACK_DAMAGE, 0.0, EquipmentSlot.MAINHAND);
  }

  private ItemStack take(Take take) {
    if (take.slot() != null) {
      ItemStack stack = player.getItemBySlot(take.slot()).copy();
      player.setItemSlot(take.slot(), ItemStack.EMPTY);
      return stack;
    }
    return player.getInventory().removeItemNoUpdate(take.inventorySlot());
  }

  private void openPortal(ServerLevel level, BlockPos at) {
    for (int dx = -1; dx <= 1; dx++) {
      for (int dz = -1; dz <= 1; dz++) {
        BlockPos pos = at.offset(dx, 0, dz);
        if (level.getBlockState(pos).canBeReplaced() && level.getFluidState(pos).isEmpty()) {
          level.setBlockAndUpdate(pos, GuestArchitects.PORTAL_BLOCK.get().defaultBlockState());
        }
      }
    }
    Vec3 c = at.getBottomCenter();
    level.sendParticles(ParticleTypes.REVERSE_PORTAL, c.x, c.y + 0.5, c.z, 40, 1.0, 0.2, 1.0, 0.02);
    level.playSound(
        null, c.x, c.y, c.z, GuestArchitects.PORTAL.get(), SoundSource.HOSTILE, 1.0F, 0.8F);
  }

  private void push() {
    AttributeInstance gravity = player.getAttribute(Attributes.GRAVITY);
    if (gravity != null) {
      gravity.removeModifier(HELD);
    }
    player.setDeltaMovement(0.0, -0.8, 0.0);
    player.hurtMarked = true;
  }

  void sendAway(ServerLevel level) {
    if (finished) {
      return;
    }
    release(false);
    CityRelations.history(level, site, player, CityRelations.REMOVAL_KIND);
    sendAway(player);
  }

  static void sendAway(ServerPlayer player) {
    ServerLevel from = player.level();
    TeleportTransition transition = null;
    if (from.getRandom().nextDouble() < ArchitectsConfig.NETHER_REMOVAL_CHANCE.get()) {
      transition = toNether(player);
    }
    if (transition == null) {
      BlockPos top =
          from.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, player.blockPosition());
      transition =
          new TeleportTransition(
              from,
              top.getBottomCenter(),
              Vec3.ZERO,
              player.getYRot(),
              player.getXRot(),
              TeleportTransition.DO_NOTHING);
    }
    effect(from, player.position());
    ServerPlayer moved = player.teleport(transition);
    if (moved != null) {
      effect(moved.level(), moved.position());
    }
  }

  private static @Nullable TeleportTransition toNether(ServerPlayer player) {
    ServerLevel nether = player.level().getServer().getLevel(Level.NETHER);
    if (nether == null) {
      return null;
    }
    int x = player.getBlockX() / 8;
    int z = player.getBlockZ() / 8;
    for (int radius = 0; radius <= 16; radius += 4) {
      for (int dx = -radius; dx <= radius; dx += 4) {
        for (int dz = -radius; dz <= radius; dz += 4) {
          BlockPos safe = safeSpot(nether, x + dx, z + dz);
          if (safe != null) {
            return new TeleportTransition(
                nether,
                safe.getBottomCenter(),
                Vec3.ZERO,
                player.getYRot(),
                player.getXRot(),
                TeleportTransition.DO_NOTHING);
          }
        }
      }
    }
    return null;
  }

  private static @Nullable BlockPos safeSpot(ServerLevel nether, int x, int z) {
    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    for (int y = 100; y > 32; y--) {
      pos.set(x, y, z);
      BlockPos below = pos.below();
      if (nether.getBlockState(pos).isAir()
          && nether.getBlockState(pos.above()).isAir()
          && nether.getBlockState(below).isFaceSturdy(nether, below, Direction.UP)
          && nether.getFluidState(below).isEmpty()
          && !nether.getBlockState(below).is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)) {
        return pos.immutable();
      }
    }
    return null;
  }

  private static void effect(ServerLevel level, Vec3 at) {
    level.sendParticles(
        ParticleTypes.REVERSE_PORTAL, at.x, at.y + 1.0, at.z, 60, 0.5, 1.0, 0.5, 0.05);
    level.sendParticles(ParticleTypes.ENCHANT, at.x, at.y + 1.0, at.z, 40, 0.6, 1.0, 0.6, 0.5);
    level.playSound(
        null, at.x, at.y, at.z, GuestArchitects.PORTAL.get(), SoundSource.HOSTILE, 1.0F, 1.0F);
  }

  public static void enterPortal(ServerPlayer player) {
    CityManager.get(player.level()).portalEntered(player);
  }
}
