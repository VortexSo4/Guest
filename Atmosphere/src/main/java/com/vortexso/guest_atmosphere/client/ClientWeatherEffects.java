package com.vortexso.guest_atmosphere.client;

import com.vortexso.guest_atmosphere.AtmosphereConfig;
import com.vortexso.guest_atmosphere.GuestAtmosphere;
import com.vortexso.guest_atmosphere.network.WeatherSyncPayload;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.world.WeatherState;
import com.vortexso.guest_core.api.world.WeatherType;
import com.vortexso.guest_core.client.GuestGizmos;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Everything the local player sees and hears of their region's weather: vanilla rain/thunder level,
 * visibility, wind-driven particles, ambient loops and the aurora. All of it fades with sky
 * exposure, so shelter and caves are quiet and clear.
 */
@EventBusSubscriber(modid = GuestAtmosphere.MODID, value = Dist.CLIENT)
public final class ClientWeatherEffects {
  /** Vanilla moves rain level by 0.01 per tick; the same pace keeps transitions familiar. */
  private static final float RAIN_STEP = 0.01F;

  private static final int[] AUTUMN_LEAVES = {
    0xFFD2691E, 0xFFB8860B, 0xFFCD5C2C, 0xFFA0522D, 0xFFDAA520, 0xFF8B4513
  };

  private static float rainLevel = -1.0F;
  private static float thunderLevel = -1.0F;
  private static float exposure;
  private static float visibility;
  private static WeatherState state = WeatherState.CLEAR;

  private static final Loop WIND = new Loop(GuestAtmosphere.WIND_SOUND);
  private static final Loop BLIZZARD = new Loop(GuestAtmosphere.BLIZZARD_SOUND);
  private static final Loop SANDSTORM = new Loop(GuestAtmosphere.SANDSTORM_SOUND);

  private ClientWeatherEffects() {}

  @SubscribeEvent
  public static void onClientTick(ClientTickEvent.Post event) {
    Minecraft minecraft = Minecraft.getInstance();
    ClientLevel level = minecraft.level;
    LocalPlayer player = minecraft.player;
    if (level == null || player == null || minecraft.isPaused()) {
      return;
    }
    WeatherSyncPayload weather = WeatherSyncPayload.latest();
    boolean active = weather.driving() && level.dimension() == Level.OVERWORLD;
    state = active ? weather.state() : WeatherState.CLEAR;

    BlockPos eye = BlockPos.containing(player.getEyePosition());
    float sky = level.getBrightness(LightLayer.SKY, eye) / 15.0F;
    exposure = approach(exposure, sky * sky, 0.05F);
    visibility = approach(visibility, visibilityLoss(state) * exposure, 0.01F);

    if (active) {
      driveVanillaWeather(level, state);
    } else {
      rainLevel = -1.0F;
      thunderLevel = -1.0F;
    }

    boolean sounds = AtmosphereConfig.AMBIENT_SOUNDS.get();
    WeatherType type = state.type();
    float wind = state.wind() * exposure;
    BLIZZARD.update(
        minecraft,
        sounds && type.isSnow() && type != WeatherType.SNOWFALL
            ? state.intensity() * exposure
            : 0.0F);
    SANDSTORM.update(
        minecraft, sounds && type == WeatherType.SANDSTORM ? state.intensity() * exposure : 0.0F);
    WIND.update(
        minecraft,
        sounds && !type.isSevere() && type != WeatherType.FOG
            ? Math.max(0.0F, wind - 0.4F) / 0.6F * 0.7F
            : 0.0F);

    if (AtmosphereConfig.WEATHER_PARTICLES.get()
        && minecraft.options.particles().get() != ParticleStatus.MINIMAL) {
      spawnParticles(level, player, weather.windAngle());
    }
  }

  @SubscribeEvent
  public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
    WeatherSyncPayload.reset();
    rainLevel = -1.0F;
    thunderLevel = -1.0F;
    state = WeatherState.CLEAR;
  }

  /** Blizzards, sandstorms and fog pull the fog in; the far plane shrinks toward a few blocks. */
  @SubscribeEvent
  public static void onRenderFog(ViewportEvent.RenderFog event) {
    if (event.getType() != FogType.ATMOSPHERIC
        || !(event.getEnvironment() instanceof AtmosphericFogEnvironment)) {
      return;
    }
    float amount = visibility * AtmosphereConfig.FOG_STRENGTH.get().floatValue();
    if (amount <= 0.001F) {
      return;
    }
    float far = Mth.lerp(amount, event.getFarPlaneDistance(), minimumVisibility(state.type()));
    event.setFarPlaneDistance(far);
    event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance(), far * 0.1F));
    event.getFogData().skyEnd = Math.min(event.getFogData().skyEnd, far);
    event.getFogData().cloudEnd = Math.min(event.getFogData().cloudEnd, far);
  }

  @SubscribeEvent
  public static void onFogColor(ViewportEvent.ComputeFogColor event) {
    float amount = visibility * AtmosphereConfig.FOG_STRENGTH.get().floatValue() * 0.8F;
    int color = fogColor(state.type());
    if (amount <= 0.001F || color == 0 || event.getCamera().getFluidInCamera() != FogType.NONE) {
      return;
    }
    // Keep the current brightness so night storms stay dark.
    float brightness = Math.max(event.getRed(), Math.max(event.getGreen(), event.getBlue()));
    event.setRed(Mth.lerp(amount, event.getRed(), (color >> 16 & 0xFF) / 255.0F * brightness));
    event.setGreen(Mth.lerp(amount, event.getGreen(), (color >> 8 & 0xFF) / 255.0F * brightness));
    event.setBlue(Mth.lerp(amount, event.getBlue(), (color & 0xFF) / 255.0F * brightness));
  }

  /**
   * Placeholder aurora: translucent curtains drawn with world-space gizmos high in the northern
   * sky. See PLACEHOLDERS.md; a proper sky renderer should replace this.
   */
  @SubscribeEvent
  public static void onRenderLevel(RenderLevelStageEvent.AfterTranslucentBlocks event) {
    Minecraft minecraft = Minecraft.getInstance();
    ClientLevel level = minecraft.level;
    if (level == null
        || !state.aurora()
        || !AtmosphereConfig.AURORA_VISUALS.get()
        || exposure < 0.05F
        || !GuestTime.isNight(GuestTime.gameTime(level))) {
      return;
    }
    Vec3 camera = minecraft.gameRenderer.getMainCamera().position();
    float time = (level.getGameTime() % 24_000L) / 40.0F;
    int alpha = (int) (40 * exposure);
    try (var ignored = minecraft.levelRenderer.collectPerFrameGizmos()) {
      for (int i = -12; i <= 12; i++) {
        double x = camera.x + i * 8.0;
        double wave = Math.sin(time + i * 0.45) * 10.0;
        double z = camera.z - 110.0 + wave;
        double bottom = camera.y + 45.0 + Math.sin(time * 0.7 + i) * 4.0;
        int green = 0x40 + (int) (0x80 * (0.5 + 0.5 * Math.sin(time * 0.3 + i * 0.2)));
        int fill = alpha << 24 | 0x30 << 16 | green << 8 | 0x90;
        GuestGizmos.box(new AABB(x, bottom, z, x + 8.0, bottom + 35.0, z + 0.5), 0, 0.0F, fill);
      }
    }
  }

  private static void driveVanillaWeather(ClientLevel level, WeatherState state) {
    WeatherType type = state.type();
    float rainTarget = type.isPrecipitation() ? 0.3F + 0.7F * state.intensity() : 0.0F;
    float thunderTarget = type == WeatherType.THUNDERSTORM ? 1.0F : 0.0F;
    if (rainLevel < 0.0F) {
      rainLevel = level.getRainLevel(1.0F);
      thunderLevel = level.getThunderLevel(1.0F);
    }
    rainLevel = approach(rainLevel, rainTarget, RAIN_STEP);
    thunderLevel = approach(thunderLevel, thunderTarget, RAIN_STEP);
    level.setRainLevel(rainLevel);
    level.setThunderLevel(thunderLevel);
  }

  private static void spawnParticles(ClientLevel level, LocalPlayer player, float windAngle) {
    WeatherType type = state.type();
    float density = AtmosphereConfig.PARTICLE_DENSITY.get().floatValue() * exposure;
    if (density <= 0.0F) {
      return;
    }
    RandomSource random = level.getRandom();
    double speed = 0.2 + 0.6 * state.wind();
    double windX = Math.cos(windAngle) * speed;
    double windZ = Math.sin(windAngle) * speed;
    Vec3 eye = player.getEyePosition();

    if (type == WeatherType.BLIZZARD
        || type == WeatherType.SNOWSTORM
        || type == WeatherType.SANDSTORM) {
      boolean sand = type == WeatherType.SANDSTORM;
      int count =
          Math.round(state.intensity() * density * (type == WeatherType.SNOWSTORM ? 14 : 9));
      BlockParticleOption sandParticle =
          sand
              ? new BlockParticleOption(
                  ParticleTypes.BLOCK,
                  (level.getBiome(player.blockPosition()).is(BiomeTags.IS_BADLANDS)
                          ? Blocks.RED_SAND
                          : Blocks.SAND)
                      .defaultBlockState())
              : null;
      for (int i = 0; i < count; i++) {
        double x = eye.x + (random.nextDouble() - 0.5) * 24.0 - windX * 10.0;
        double y = eye.y + (random.nextDouble() - 0.3) * 10.0;
        double z = eye.z + (random.nextDouble() - 0.5) * 24.0 - windZ * 10.0;
        if (!level.canSeeSky(BlockPos.containing(x, y, z))) {
          continue;
        }
        if (sand) {
          level.addParticle(sandParticle, x, y, z, windX * 2.0, 0.02, windZ * 2.0);
        } else {
          level.addParticle(ParticleTypes.SNOWFLAKE, x, y, z, windX, -0.08, windZ);
        }
      }
    } else if (type == WeatherType.LEAF_FALL) {
      int attempts = Math.round(state.intensity() * density * 16);
      BlockPos origin = player.blockPosition();
      for (int i = 0; i < attempts; i++) {
        BlockPos pos =
            origin.offset(random.nextInt(21) - 10, random.nextInt(14) - 2, random.nextInt(21) - 10);
        if (level.getBlockState(pos).is(BlockTags.LEAVES)
            && level.getBlockState(pos.below()).isAir()) {
          int color = AUTUMN_LEAVES[random.nextInt(AUTUMN_LEAVES.length)];
          level.addParticle(
              ColorParticleOption.create(ParticleTypes.TINTED_LEAVES, color),
              pos.getX() + random.nextDouble(),
              pos.getY() - 0.05,
              pos.getZ() + random.nextDouble(),
              0.0,
              0.0,
              0.0);
        }
      }
    }
  }

  /** 0..1 share of the normal view distance lost at full intensity. */
  private static float visibilityLoss(WeatherState state) {
    float intensity = state.intensity();
    return switch (state.type()) {
      case FOG, BLIZZARD, SNOWSTORM, SANDSTORM -> intensity;
      case DOWNPOUR, THUNDERSTORM -> 0.4F * intensity;
      case SNOWFALL -> 0.3F * intensity;
      default -> 0.0F;
    };
  }

  private static float minimumVisibility(WeatherType type) {
    return switch (type) {
      case SNOWSTORM -> 10.0F;
      case SANDSTORM -> 12.0F;
      case BLIZZARD -> 18.0F;
      case FOG -> 24.0F;
      default -> 48.0F;
    };
  }

  private static int fogColor(WeatherType type) {
    return switch (type) {
      case FOG -> 0xB4B8BE;
      case BLIZZARD, SNOWSTORM, SNOWFALL -> 0xDDE2EA;
      case SANDSTORM -> 0xCFA56E;
      default -> 0;
    };
  }

  private static float approach(float value, float target, float step) {
    return value + Mth.clamp(target - value, -step, step);
  }

  /** One ambient loop; restarted when needed, fades itself out and stops when silent. */
  private static final class Loop {
    private final Supplier<SoundEvent> sound;
    private WeatherLoopSound instance;
    private float target;

    Loop(Supplier<SoundEvent> sound) {
      this.sound = sound;
    }

    void update(Minecraft minecraft, float target) {
      this.target = target;
      if (target > 0.02F
          && (instance == null
              || instance.isStopped()
              || !minecraft.getSoundManager().isActive(instance))) {
        instance = new WeatherLoopSound(sound.get(), () -> this.target);
        minecraft.getSoundManager().play(instance);
      }
    }
  }
}
