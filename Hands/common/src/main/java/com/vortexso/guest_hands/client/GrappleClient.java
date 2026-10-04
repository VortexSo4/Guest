package com.vortexso.guest_hands.client;

import com.vortexso.guest_hands.HandsConfig;
import com.vortexso.guest_hands.grapple.Grapple;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class GrappleClient {

  private static final double VERTICAL_DEAD_ZONE = 0.3;

  private static final double WALL_PULL = 0.1;

  private static final double MANTLE_PUSH = 0.2;

  private static Grapple.@Nullable Anchor anchor;

  private GrappleClient() {}

  public static Grapple.@Nullable Anchor anchor() {
    return anchor;
  }

  public static void onServerDetach() {
    anchor = null;
  }

  public static boolean onUse() {
    return anchor != null || tryAttach();
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
    send(new Grapple.GrapplePayload(true, found.pos()));
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

  public static void tick(Player entity) {
    Minecraft minecraft = Minecraft.getInstance();
    if (anchor == null || entity != minecraft.player) {
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

        player.setDeltaMovement(anchor.face().getUnitVec3().scale(-MANTLE_PUSH).add(0, 0.42, 0));
      }
      release();
      return;
    }
    if (!next.pos().equals(anchor.pos())) {
      send(new Grapple.GrapplePayload(true, next.pos()));
    }
    anchor = next;
    player.setDeltaMovement(gripVelocity(player, next));
    player.resetFallDistance();
  }

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
    send(new Grapple.GrapplePayload(false, BlockPos.ZERO));
  }

  private static void send(Grapple.GrapplePayload payload) {
    ClientPacketListener connection = Minecraft.getInstance().getConnection();
    if (connection != null) {
      connection.send(new ServerboundCustomPayloadPacket(payload));
    }
  }
}
