package com.vortexso.guest_atmosphere.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.vortexso.guest_atmosphere.AtmosphereConfig;
import com.vortexso.guest_core.api.GuestTime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;

public final class AuroraRenderer {
  private static final int CURTAINS = 3;
  private static final int SEGMENTS = 72;

  private static final double HALF_SPAN = 1.3;

  private static final int GREEN = 0x3CFF9A;
  private static final int VIOLET = 0xB05CFF;
  private static final float MAX_ALPHA = 0.5F;

  private static float visibility;

  private AuroraRenderer() {}

  public static void tick() {
    ClientLevel level = Minecraft.getInstance().level;
    boolean shown =
        level != null
            && AtmosphereConfig.AURORA_VISUALS.get()
            && ClientWeatherEffects.state().aurora()
            && GuestTime.isNight(GuestTime.gameTime(level));
    visibility += Mth.clamp((shown ? 1.0F : 0.0F) - visibility, -0.01F, 0.01F);
  }

  public static void renderSky() {
    float strength = visibility * ClientWeatherEffects.exposure() * WeatherFog.skyVisibility();
    ClientLevel level = Minecraft.getInstance().level;
    if (strength < 0.01F || level == null) {
      return;
    }
    float time =
        (level.getGameTime()
                + Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false))
            / 20.0F;
    MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
    RenderType type = RenderTypes.debugQuads();
    VertexConsumer buffer = buffers.getBuffer(type);
    PoseStack.Pose pose = new PoseStack().last();
    for (int k = 0; k < CURTAINS; k++) {
      double distance = 140.0 + 35.0 * k;
      double height = 55.0 + 12.0 * k;
      Column previous = null;
      for (int s = 0; s <= SEGMENTS; s++) {
        double along = s / (double) SEGMENTS;
        double angle = Mth.lerp(along, -HALF_SPAN, HALF_SPAN);

        double sway =
            0.10 * Math.sin(3.1 * angle + time * 0.35 + k * 1.7)
                + 0.05 * Math.sin(7.3 * angle - time * 0.6 + k);
        double bend = angle + 0.3 * sway;
        double radius = distance * (1.0 + 0.15 * sway);
        double bottom = height + 8.0 * Math.sin(2.0 * angle + time * 0.2 + k);
        double rays =
            0.5
                + 0.5
                    * Math.sin(23.0 * angle + time * 1.3 + k * 2.1)
                    * Math.sin(11.0 * angle - time * 0.7);
        double glow =
            (0.35 + 0.65 * (0.5 + 0.5 * Math.sin(5.0 * angle - time * 0.5 + k)))
                * Math.sin(Math.PI * along);
        Column column =
            new Column(
                (float) (Math.sin(bend) * radius),
                (float) (-Math.cos(bend) * radius),
                (float) bottom,
                (float) (bottom + 35.0 + 30.0 * rays),
                (float) (MAX_ALPHA * glow * strength));
        if (previous != null) {

          band(buffer, pose, previous, column, -8.0F, 0.0F, 0.0F, 1.0F, GREEN, GREEN);
          band(buffer, pose, previous, column, 0.0F, 1.0F, 1.0F, 0.0F, GREEN, VIOLET);
        }
        previous = column;
      }
    }
    buffers.endBatch(type);
  }

  private static void band(
      VertexConsumer buffer,
      PoseStack.Pose pose,
      Column a,
      Column b,
      float from,
      float to,
      float alphaFrom,
      float alphaTo,
      int colorFrom,
      int colorTo) {
    float aLow = a.y(from);
    float aHigh = a.y(to);
    float bLow = b.y(from);
    float bHigh = b.y(to);
    buffer
        .addVertex(pose, a.x(), aLow, a.z())
        .setColor(ARGB.color(a.alpha() * alphaFrom, colorFrom));
    buffer.addVertex(pose, a.x(), aHigh, a.z()).setColor(ARGB.color(a.alpha() * alphaTo, colorTo));
    buffer.addVertex(pose, b.x(), bHigh, b.z()).setColor(ARGB.color(b.alpha() * alphaTo, colorTo));
    buffer
        .addVertex(pose, b.x(), bLow, b.z())
        .setColor(ARGB.color(b.alpha() * alphaFrom, colorFrom));
  }

  private record Column(float x, float z, float bottom, float top, float alpha) {
    float y(float position) {
      return position <= 0.0F ? bottom + position : bottom + (top - bottom) * position;
    }
  }
}
