package com.vortexso.guest_hands.client;

import com.vortexso.guest_core.client.GuestGizmos;
import com.vortexso.guest_core.debug.GuestDebug;
import com.vortexso.guest_hands.GuestHands;
import com.vortexso.guest_hands.grapple.Grapple;
import com.vortexso.guest_hands.sleep.SleepPass;
import com.vortexso.guest_hands.station.CraftingGrid;
import com.vortexso.guest_hands.station.StationDisplays;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * {@code /guest debug hands}: grapple anchor and gaze, crafting grid cells, station display
 * anchors, and the running night pass. Server-side values are read from the integrated server
 * (singleplayer only), like the other Guest debug renderers.
 */
@EventBusSubscriber(modid = GuestHands.MODID, value = Dist.CLIENT)
public final class HandsDebugRenderer {
  private static final int ANCHOR_COLOR = 0xFF55FF55;
  private static final int LOOK_COLOR = 0xFFFFFF55;
  private static final int CELL_COLOR = 0xFF55FFFF;
  private static final int LINK_COLOR = 0xFFFF55FF;
  private static final int TEXT_COLOR = 0xFFFFFFFF;
  private static final int TABLE_RADIUS = 8;
  private static final double DISPLAY_RADIUS = 16.0;

  private HandsDebugRenderer() {}

  @SubscribeEvent
  static void render(RenderLevelStageEvent.AfterTranslucentBlocks event) {
    Minecraft minecraft = Minecraft.getInstance();
    LocalPlayer player = minecraft.player;
    if (!GuestDebug.isEnabled(GuestHands.DEBUG_CHANNEL)
        || player == null
        || minecraft.level == null) {
      return;
    }
    MinecraftServer server = minecraft.getSingleplayerServer();
    ServerLevel serverLevel = server == null ? null : server.getLevel(minecraft.level.dimension());
    try (var ignored = minecraft.levelRenderer.collectPerFrameGizmos()) {
      renderGrapple(player, serverLevel);
      renderCraftingCells(minecraft, player);
      if (serverLevel != null) {
        renderStationDisplays(serverLevel, player);
        renderNightPass(serverLevel, player);
      }
    }
  }

  private static void renderGrapple(LocalPlayer player, ServerLevel serverLevel) {
    Grapple.Anchor anchor = GrappleClient.anchor();
    if (anchor == null) {
      return;
    }
    GuestGizmos.box(new AABB(anchor.pos()), ANCHOR_COLOR);
    Vec3 face = Vec3.atCenterOf(anchor.pos()).add(anchor.face().getUnitVec3().scale(0.5));
    GuestGizmos.point(face, ANCHOR_COLOR, 6.0F);
    Vec3 eye = player.getEyePosition();
    GuestGizmos.arrow(eye, eye.add(player.getLookAngle().scale(1.5)), LOOK_COLOR);
    GuestGizmos.arrow(
        player.position(), player.position().add(player.getDeltaMovement().scale(10)), CELL_COLOR);
    if (serverLevel != null
        && serverLevel.getPlayerByUUID(player.getUUID()) instanceof ServerPlayer serverPlayer) {
      Grapple.Attachment attachment = Grapple.attachment(serverPlayer);
      if (attachment != null) {
        GuestGizmos.text(
            Component.translatable(
                "guest_hands.debug.grapple",
                String.format(Locale.ROOT, "%.2f", attachment.carried)),
            face.add(0, 0.4, 0),
            TEXT_COLOR);
      }
    }
  }

  private static void renderCraftingCells(Minecraft minecraft, LocalPlayer player) {
    BlockPos center = player.blockPosition();
    for (BlockPos pos :
        BlockPos.betweenClosed(
            center.offset(-TABLE_RADIUS, -TABLE_RADIUS, -TABLE_RADIUS),
            center.offset(TABLE_RADIUS, TABLE_RADIUS, TABLE_RADIUS))) {
      if (!minecraft.level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
        continue;
      }
      for (int cell = 0; cell < 9; cell++) {
        double x = pos.getX() + CraftingGrid.cellCenter(cell % 3);
        double z = pos.getZ() + CraftingGrid.cellCenter(cell / 3);
        double half = 1.0 / 6.0 - 0.01;
        GuestGizmos.box(
            new AABB(x - half, pos.getY() + 1.0, z - half, x + half, pos.getY() + 1.02, z + half),
            CELL_COLOR,
            1.0F);
      }
    }
  }

  private static void renderStationDisplays(ServerLevel level, LocalPlayer player) {
    for (Display display :
        level.getEntitiesOfClass(
            Display.class,
            player.getBoundingBox().inflate(DISPLAY_RADIUS),
            display -> display.entityTags().contains(StationDisplays.MARKER))) {
      StationDisplays.Key key = StationDisplays.Key.of(display);
      if (key == null) {
        continue;
      }
      Vec3 anchor = Vec3.atCenterOf(key.pos());
      GuestGizmos.line(display.position(), anchor, LINK_COLOR, 1.0F);
      GuestGizmos.text(
          key.station() + "/" + key.role(), display.position().add(0, 0.15, 0), TEXT_COLOR, 0.2F);
    }
  }

  private static void renderNightPass(ServerLevel level, LocalPlayer player) {
    SleepPass.Pass pass = SleepPass.pass(level);
    if (pass == null) {
      return;
    }
    GuestGizmos.text(
        Component.translatable(
            "guest_hands.debug.night_pass",
            pass.elapsed,
            pass.target - pass.start,
            Component.translatable(
                pass.done ? "guest_hands.debug.done" : "guest_hands.debug.running")),
        player.getEyePosition().add(player.getLookAngle().scale(2.0)),
        TEXT_COLOR);
  }
}
