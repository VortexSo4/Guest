package com.vortexso.guest_hands.client;

import com.vortexso.guest_hands.GuestHands;
import com.vortexso.guest_hands.HandsConfig;
import com.vortexso.guest_hands.grapple.Grapple;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * Client half of grappling: the local player moves itself (player motion is client-authoritative)
 * following the gaze while the use key is held, and tells the server which block it holds.
 */
@EventBusSubscriber(modid = GuestHands.MODID, value = Dist.CLIENT)
public final class GrappleClient {
  /** |look.y| below this is "looking sideways": no vertical intent, so the grip holds height. */
  private static final double VERTICAL_DEAD_ZONE = 0.3;

  /**
   * Per-tick pull toward the wall, keeps the body in contact (and the server's float check calm).
   */
  private static final double WALL_PULL = 0.1;

  /** Horizontal push onto the ledge when climbing over the top; 0.42 up is the vanilla jump. */
  private static final double MANTLE_PUSH = 0.2;

  private static Grapple.@Nullable Anchor anchor;

  private GrappleClient() {}

  public static Grapple.@Nullable Anchor anchor() {
    return anchor;
  }

  public static void onServerDetach() {
    anchor = null;
  }

  @SubscribeEvent
  static void onUse(InputEvent.InteractionKeyMappingTriggered event) {
    if (!event.isUseItem() || event.getHand() != InteractionHand.MAIN_HAND) {
      return;
    }
    if (anchor != null || tryAttach()) {
      // The grip is the use action: no block placement from either hand while hanging.
      event.setCanceled(true);
      event.setSwingHand(false);
    }
  }

  private static boolean tryAttach() {
    Minecraft minecraft = Minecraft.getInstance();
    LocalPlayer player = minecraft.player;
    if (player == null
        || minecraft.level == null
        || !HandsConfig.GRAPPLE_ENABLED.get()
        || !canGrapple(player)
        || targetsInteractiveBlock(minecraft)) {
      return false;
    }
    Grapple.Anchor found =
        Grapple.findAnchor(
            minecraft.level, player, HandsConfig.GRAPPLE_REACH.get(), crosshairBlock(minecraft));
    if (found == null) {
      return false;
    }
    anchor = found;
    ClientPacketDistributor.sendToServer(new Grapple.GrapplePayload(true, found.pos()));
    return true;
  }

  private static boolean canGrapple(LocalPlayer player) {
    return Grapple.holdsPickaxe(player)
        && !player.isSpectator()
        && !player.getAbilities().flying
        && !player.isPassenger()
        && !player.isFallFlying()
        && !player.isInWater();
  }

  /** Right-clicking a chest or workstation with a pickaxe should still open it. */
  private static boolean targetsInteractiveBlock(Minecraft minecraft) {
    BlockPos pos = crosshairBlock(minecraft);
    return pos != null
        && minecraft.level.getBlockState(pos).getMenuProvider(minecraft.level, pos) != null;
  }

  private static @Nullable BlockPos crosshairBlock(Minecraft minecraft) {
    return minecraft.hitResult instanceof BlockHitResult hit
            && hit.getType() == HitResult.Type.BLOCK
        ? hit.getBlockPos()
        : null;
  }

  @SubscribeEvent
  static void tick(PlayerTickEvent.Pre event) {
    Minecraft minecraft = Minecraft.getInstance();
    if (anchor == null || event.getEntity() != minecraft.player) {
      return;
    }
    LocalPlayer player = minecraft.player;
    if (!minecraft.options.keyUse.isDown()
        || minecraft.screen != null
        || !HandsConfig.GRAPPLE_ENABLED.get()
        || !canGrapple(player)) {
      release();
      return;
    }
    Grapple.Anchor next =
        Grapple.findAnchor(player.level(), player, HandsConfig.GRAPPLE_REACH.get(), anchor.pos());
    if (next == null) {
      if (player.getLookAngle().y > VERTICAL_DEAD_ZONE) {
        // Climbed past the top edge: pull up over it with a jump-sized boost, as a player would.
        player.setDeltaMovement(anchor.face().getUnitVec3().scale(-MANTLE_PUSH).add(0, 0.42, 0));
      }
      release();
      return;
    }
    if (!next.pos().equals(anchor.pos())) {
      ClientPacketDistributor.sendToServer(new Grapple.GrapplePayload(true, next.pos()));
    }
    anchor = next;
    player.setDeltaMovement(gripVelocity(player, next));
    player.resetFallDistance();
  }

  /**
   * Gaze decides direction: up climbs, down descends, sideways slides along the wall face. Faster
   * falling than the target is braked gradually, which is the "slide a little, then stop" catch.
   */
  private static Vec3 gripVelocity(LocalPlayer player, Grapple.Anchor grip) {
    Vec3 look = player.getLookAngle();
    Vec3 normal = grip.face().getUnitVec3();

    double targetY = 0.0;
    if (look.y > VERTICAL_DEAD_ZONE) {
      targetY =
          HandsConfig.CLIMB_SPEED.get() * (look.y - VERTICAL_DEAD_ZONE) / (1 - VERTICAL_DEAD_ZONE);
    } else if (look.y < -VERTICAL_DEAD_ZONE) {
      targetY =
          HandsConfig.DESCEND_SPEED.get()
              * (look.y + VERTICAL_DEAD_ZONE)
              / (1 - VERTICAL_DEAD_ZONE);
    }
    double currentY = player.getDeltaMovement().y;
    double y =
        currentY < targetY
            ? Math.min(targetY, currentY + HandsConfig.BRAKE_DECELERATION.get())
            : targetY;

    Vec3 horizontal = new Vec3(look.x, 0.0, look.z);
    Vec3 along = horizontal.subtract(normal.scale(horizontal.dot(normal)));
    if (Math.abs(look.y) > 1 - VERTICAL_DEAD_ZONE) {
      along = Vec3.ZERO;
    }
    Vec3 side = along.scale(HandsConfig.SIDE_SPEED.get());
    double gap = Grapple.gap(player.getBoundingBox(), grip.pos());
    Vec3 pull = normal.scale(-Math.min(gap, WALL_PULL));
    return new Vec3(side.x + pull.x, y, side.z + pull.z);
  }

  private static void release() {
    anchor = null;
    ClientPacketDistributor.sendToServer(new Grapple.GrapplePayload(false, BlockPos.ZERO));
  }
}
