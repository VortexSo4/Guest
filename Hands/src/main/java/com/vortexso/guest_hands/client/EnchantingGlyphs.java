package com.vortexso.guest_hands.client;

import com.vortexso.guest_hands.GuestHands;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jspecify.annotations.Nullable;

@EventBusSubscriber(modid = GuestHands.MODID, value = Dist.CLIENT)
public final class EnchantingGlyphs {

  private static final float STREAM_CHANCE = 0.35F;

  private static final int STREAM_LIFETIME = 10;

  private static final float STREAM_SCALE = 1.5F;

  private static final int BURST_PER_SPOT = 3;
  private static final int BURST_LIFETIME = 8;
  private static final float BURST_SCALE = 2.0F;
  private static final int SEARCH_RADIUS = 6;

  private static @Nullable EnchantmentMenu menu;
  private static @Nullable BlockPos table;
  private static ItemStack lastItem = ItemStack.EMPTY;

  private EnchantingGlyphs() {}

  @SubscribeEvent
  static void tick(ClientTickEvent.Post event) {
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.player == null
        || minecraft.level == null
        || !(minecraft.player.containerMenu instanceof EnchantmentMenu open)) {
      menu = null;
      return;
    }
    if (open != menu) {
      menu = open;
      table = findTable(minecraft);
      lastItem = ItemStack.EMPTY;
    }
    ItemStack item = open.getSlot(0).getItem();
    boolean enchantedNow =
        !lastItem.isEmpty()
            && !EnchantmentHelper.hasAnyEnchantments(lastItem)
            && EnchantmentHelper.hasAnyEnchantments(item);
    lastItem = item.copy();
    if (table == null
        || minecraft.isPaused()
        || minecraft.options.particles().get() == ParticleStatus.MINIMAL) {
      return;
    }
    ClientLevel level = minecraft.level;
    RandomSource random = level.getRandom();
    for (BlockPos offset : EnchantingTableBlock.BOOKSHELF_OFFSETS) {
      if (enchantedNow) {

        for (int i = 0; i < BURST_PER_SPOT; i++) {
          glyph(minecraft, table, offset, random, BURST_LIFETIME, BURST_SCALE);
        }
      } else if (!item.isEmpty()
          && random.nextFloat() < STREAM_CHANCE
          && EnchantingTableBlock.isValidBookShelf(level, table, offset)) {
        glyph(minecraft, table, offset, random, STREAM_LIFETIME + random.nextInt(4), STREAM_SCALE);
      }
    }
  }

  private static void glyph(
      Minecraft minecraft,
      BlockPos table,
      BlockPos offset,
      RandomSource random,
      int lifetime,
      float scale) {
    Particle particle =
        minecraft.particleEngine.createParticle(
            ParticleTypes.ENCHANT,
            table.getX() + 0.5,
            table.getY() + 2.0,
            table.getZ() + 0.5,
            offset.getX() + random.nextFloat() - 0.5,
            offset.getY() - random.nextFloat() - 1.0F,
            offset.getZ() + random.nextFloat() - 0.5);
    if (particle != null) {
      particle.setLifetime(lifetime);
      particle.scale(scale);
    }
  }

  private static @Nullable BlockPos findTable(Minecraft minecraft) {
    if (minecraft.hitResult instanceof BlockHitResult hit
        && hit.getType() == HitResult.Type.BLOCK
        && minecraft.level.getBlockState(hit.getBlockPos()).is(Blocks.ENCHANTING_TABLE)) {
      return hit.getBlockPos();
    }
    BlockPos center = minecraft.player.blockPosition();
    BlockPos nearest = null;
    for (BlockPos pos :
        BlockPos.betweenClosed(
            center.offset(-SEARCH_RADIUS, -SEARCH_RADIUS, -SEARCH_RADIUS),
            center.offset(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS))) {
      if (minecraft.level.getBlockState(pos).is(Blocks.ENCHANTING_TABLE)
          && (nearest == null || pos.distSqr(center) < nearest.distSqr(center))) {
        nearest = pos.immutable();
      }
    }
    return nearest;
  }
}
