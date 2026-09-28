package com.vortexso.guest_atmosphere.client;

import com.vortexso.guest_atmosphere.AtmosphereConfig;
import com.vortexso.guest_atmosphere.GuestAtmosphere;
import com.vortexso.guest_atmosphere.network.WeatherSyncPayload;
import com.vortexso.guest_core.api.world.WeatherState;
import com.vortexso.guest_core.api.world.WeatherType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.Tags;

@EventBusSubscriber(modid = GuestAtmosphere.MODID, value = Dist.CLIENT)
public final class WeatherFog {

  private static final float NO_FOG = 1024.0F;

  private static final float LOG_NO_FOG = (float) Math.log(NO_FOG);

  private static final float LOG_STEP = 0.06F;

  private static final float COLOR_STEP = 0.05F;

  private static final float NEAR_FRACTION = 0.1F;

  private static final float SKY_FRACTION = 0.5F;

  private static final float CLOUD_FRACTION = 1.5F;

  private static final float LOG_SKY_HIDDEN = (float) Math.log(16.0);

  private static final float LOG_SKY_VISIBLE = (float) Math.log(160.0);

  private static final int SWAMP_FOG = 0x8E9C86;
  private static final int DARK_FOREST_FOG = 0x949C95;
  private static final int FROST_FOG = 0xDCE1E6;
  private static final int GREY_FOG = 0xC0C5CA;
  private static final int RAIN_FOG = 0x8F969E;
  private static final int SWAMP_RAIN_FOG = 0x7F8B7C;
  private static final int SNOW_FOG = 0xD9DEE5;
  private static final int SNOWSTORM_FOG = 0xDDE3EA;
  private static final int BLIZZARD_FOG = 0xD3DAE2;
  private static final int SAND_FOG = 0xD9A45E;
  private static final int RED_SAND_FOG = 0xC4703E;
  private static final int HEAT_HAZE = 0xEDE0C4;

  private static float logEnd = LOG_NO_FOG;

  private static float red = 1.0F;
  private static float green = 1.0F;
  private static float blue = 1.0F;
  private static float colorWeight;
  private static boolean bright;
  private static float heat;

  private static float appliedStart;

  private static float appliedEnd = NO_FOG;
  private static float skyVisibility = 1.0F;
  private static int appliedColor = 0xFFFFFFFF;

  private WeatherFog() {}

  public static float fogStart() {
    return appliedStart;
  }

  public static float fogEnd() {
    return appliedEnd;
  }

  public static float skyVisibility() {
    return skyVisibility;
  }

  public static int fogColor() {
    return appliedColor;
  }

  public static float fogAt(double distance) {
    if (distance <= appliedStart) {
      return 0.0F;
    }
    return (float) Math.min(1.0, (distance - appliedStart) / (appliedEnd - appliedStart));
  }

  @SubscribeEvent
  public static void onClientTick(ClientTickEvent.Post event) {
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.level == null || minecraft.isPaused()) {
      return;
    }
    BlockPos camera = minecraft.gameRenderer.getMainCamera().blockPosition();
    Profile profile =
        profile(
            ClientWeatherEffects.state(),
            WeatherSyncPayload.latest().temperature(),
            minecraft.level.getBiome(camera));
    logEnd += Mth.clamp((float) Math.log(profile.end()) - logEnd, -LOG_STEP, LOG_STEP);
    red = approach(red, ARGB.redFloat(profile.color()), COLOR_STEP);
    green = approach(green, ARGB.greenFloat(profile.color()), COLOR_STEP);
    blue = approach(blue, ARGB.blueFloat(profile.color()), COLOR_STEP);
    colorWeight = approach(colorWeight, profile.colorWeight(), COLOR_STEP);
    bright = profile.bright();
    WeatherState state = ClientWeatherEffects.state();
    heat = approach(heat, state.type() == WeatherType.HEAT ? state.intensity() : 0.0F, 0.01F);
  }

  @SubscribeEvent
  public static void onRenderFog(ViewportEvent.RenderFog event) {
    if (event.getType() != FogType.ATMOSPHERIC) {
      skyVisibility = 1.0F;
      appliedStart = event.getNearPlaneDistance();
      appliedEnd = event.getFarPlaneDistance();
      return;
    }
    float amount = strength();
    float vanillaEnd = event.getFarPlaneDistance();
    float logVanilla = (float) Math.log(Math.max(1.0F, vanillaEnd));
    float end = (float) Math.exp(Mth.lerp(amount, logVanilla, Math.min(logEnd, logVanilla)));
    if (end < vanillaEnd - 0.5F) {
      FogData data = event.getFogData();

      float nearShare =
          Mth.lerp(
              amount, event.getNearPlaneDistance() / Math.max(1.0F, vanillaEnd), NEAR_FRACTION);
      event.setNearPlaneDistance(nearShare * end);
      event.setFarPlaneDistance(end);
      data.skyEnd = Math.min(data.skyEnd, end * SKY_FRACTION);
      data.cloudEnd = Math.min(data.cloudEnd, end * CLOUD_FRACTION);
    }
    appliedStart = event.getNearPlaneDistance();
    appliedEnd = event.getFarPlaneDistance();
    skyVisibility =
        Mth.clamp(
            ((float) Math.log(appliedEnd) - LOG_SKY_HIDDEN) / (LOG_SKY_VISIBLE - LOG_SKY_HIDDEN),
            0.0F,
            1.0F);
  }

  @SubscribeEvent
  public static void onFogColor(ViewportEvent.ComputeFogColor event) {
    float r = event.getRed();
    float g = event.getGreen();
    float b = event.getBlue();
    float amount = strength() * colorWeight;
    if (amount > 0.001F && event.getCamera().getFluidInCamera() == FogType.NONE) {

      float brightness = Math.max(r, Math.max(g, b));
      if (bright && Minecraft.getInstance().level != null) {
        brightness =
            Math.min(
                1.0F,
                brightness / (1.0F - 0.4F * Minecraft.getInstance().level.getRainLevel(1.0F)));
      }
      r = Mth.lerp(amount, r, red * brightness);
      g = Mth.lerp(amount, g, green * brightness);
      b = Mth.lerp(amount, b, blue * brightness);
      event.setRed(r);
      event.setGreen(g);
      event.setBlue(b);
    }
    appliedColor = ARGB.colorFromFloat(1.0F, r, g, b);
  }

  @SubscribeEvent
  public static void onExtract(ExtractLevelRenderStateEvent event) {
    SkyRenderState sky = event.getRenderState().skyRenderState;
    if (sky.skybox != DimensionType.Skybox.OVERWORLD) {
      return;
    }
    float hidden = 1.0F - skyVisibility;
    if (hidden > 0.0F) {
      sky.rainBrightness *= skyVisibility;
      sky.starBrightness *= skyVisibility;
      sky.sunriseAndSunsetColor = ARGB.multiplyAlpha(sky.sunriseAndSunsetColor, skyVisibility);
      sky.skyColor = ARGB.srgbLerp(hidden, sky.skyColor, appliedColor);
    }
    float muted = heat * strength() * 0.45F;
    if (muted > 0.0F) {
      int grey = ARGB.greyscale(sky.skyColor);
      sky.skyColor =
          ARGB.srgbLerp(muted, sky.skyColor, ARGB.multiply(grey, HEAT_HAZE | 0xFF000000));
    }
  }

  private static float strength() {
    return ClientWeatherEffects.exposure() * AtmosphereConfig.FOG_STRENGTH.get().floatValue();
  }

  private record Profile(float end, int color, float colorWeight, boolean bright) {
    static final Profile NONE = new Profile(NO_FOG, GREY_FOG, 0.0F, false);
  }

  private static Profile profile(WeatherState state, float temperature, Holder<Biome> biome) {
    float intensity = state.intensity();
    boolean swamp = biome.is(Tags.Biomes.IS_SWAMP);
    boolean darkForest = biome.is(Biomes.DARK_FOREST) || biome.is(Biomes.PALE_GARDEN);
    return switch (state.type()) {
      case SNOWSTORM -> new Profile(storm(intensity, 12.0F, 8.0F), SNOWSTORM_FOG, 0.95F, true);
      case BLIZZARD -> new Profile(storm(intensity, 16.0F, 10.0F), BLIZZARD_FOG, 0.9F, true);
      case SANDSTORM ->
          new Profile(
              storm(intensity, 14.0F, 9.0F),
              biome.is(BiomeTags.IS_BADLANDS) ? RED_SAND_FOG : SAND_FOG,
              0.95F,
              true);
      case FOG -> {
        float end =
            fade(
                smoothstep(intensity / 0.4F),
                Mth.lerp(intensity, 64.0F, 16.0F) * (swamp || darkForest ? 0.6F : 1.0F));
        int color =
            swamp
                ? SWAMP_FOG
                : darkForest ? DARK_FOREST_FOG : temperature < 0.15F ? FROST_FOG : GREY_FOG;
        yield new Profile(end, color, 0.85F, false);
      }
      case SNOWFALL ->
          new Profile(fade(intensity, Mth.lerp(intensity, 96.0F, 32.0F)), SNOW_FOG, 0.6F, true);
      case DRIZZLE -> rain(intensity, 160.0F, 0.3F, swamp);
      case RAIN -> rain(intensity, 96.0F, 0.45F, swamp);
      case DOWNPOUR -> rain(intensity, 48.0F, 0.6F, swamp);
      case THUNDERSTORM -> rain(intensity, 40.0F, 0.6F, swamp);
      case HEAT -> new Profile(NO_FOG, HEAT_HAZE, 0.3F * intensity, true);
      default ->
          swamp
              ? new Profile(128.0F, SWAMP_FOG, 0.35F, false)
              : darkForest ? new Profile(96.0F, DARK_FOREST_FOG, 0.3F, false) : Profile.NONE;
    };
  }

  private static Profile rain(float intensity, float end, float colorWeight, boolean swamp) {
    return new Profile(
        fade(intensity, end * (swamp ? 0.5F : 1.0F)),
        swamp ? SWAMP_RAIN_FOG : RAIN_FOG,
        colorWeight,
        false);
  }

  private static float storm(float intensity, float weakEnd, float strongEnd) {
    return fade(smoothstep((intensity - 0.05F) / 0.35F), Mth.lerp(intensity, weakEnd, strongEnd));
  }

  private static float fade(float amount, float end) {
    return (float) Math.exp(Mth.lerp(amount, LOG_NO_FOG, (float) Math.log(end)));
  }

  private static float smoothstep(float t) {
    float c = Mth.clamp(t, 0.0F, 1.0F);
    return c * c * (3.0F - 2.0F * c);
  }

  private static float approach(float value, float target, float step) {
    return value + Mth.clamp(target - value, -step, step);
  }
}
