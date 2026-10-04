package com.vortexso.guest_atmosphere.client;

import com.vortexso.guest_atmosphere.AtmosphereConfig;
import com.vortexso.guest_atmosphere.GuestAtmosphere;
import com.vortexso.guest_atmosphere.network.WeatherSyncPayload;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.world.WeatherState;
import com.vortexso.guest_core.api.world.WeatherType;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class ClientWeatherEffects {

  private static final float RAIN_STEP = 0.01F;

  private static final double SNOW_DRIFT = 0.4;

  private static final int SOUND_PROBES = 24;

  private static final int PROBE_RADIUS = 12;

  private static final float CREAK_WIND = 0.45F;

  private static final float BELL_WIND = 0.3F;

  private static final int[] AUTUMN_LEAVES = {
    0xFFD2691E, 0xFFB8860B, 0xFFCD5C2C, 0xFFA0522D, 0xFFDAA520, 0xFF8B4513
  };

  private static float rainLevel = -1.0F;
  private static float thunderLevel = -1.0F;
  private static float exposure;
  private static WeatherState state = WeatherState.CLEAR;

  private static double driftX;

  private static double driftZ;
  private static double driftSpeedX;
  private static double driftSpeedZ;

  private static float vegetation;

  private static int creakCooldown;

  private static final Loop WIND = new Loop(GuestAtmosphere.WIND_SOUND);
  private static final Loop BLIZZARD = new Loop(GuestAtmosphere.BLIZZARD_SOUND);
  private static final Loop SANDSTORM = new Loop(GuestAtmosphere.SANDSTORM_SOUND);
  private static final Loop INSECTS = new Loop(GuestAtmosphere.INSECTS_SOUND);

  private ClientWeatherEffects() {}

  public static WeatherState state() {
    return state;
  }

  public static float exposure() {
    return exposure;
  }

  public static void tick() {
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

    if (active) {
      driveVanillaWeather(level, state);
    } else {
      rainLevel = -1.0F;
      thunderLevel = -1.0F;
    }

    WeatherType type = state.type();
    double drift =
        type.isSnow()
            ? SNOW_DRIFT
                * state.wind()
                * state.wind()
                * (type == WeatherType.SNOWFALL ? 1.0 : 1.3)
                * AtmosphereConfig.WIND_DRIFT.get()
            : 0.0;
    driftSpeedX = approach(driftSpeedX, Math.cos(weather.windAngle()) * drift, 0.01);
    driftSpeedZ = approach(driftSpeedZ, Math.sin(weather.windAngle()) * drift, 0.01);
    driftX += driftSpeedX;
    driftZ += driftSpeedZ;

    boolean sounds = AtmosphereConfig.AMBIENT_SOUNDS.get();
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
    probeSurroundings(level, player, sounds ? wind : 0.0F);
    INSECTS.update(
        minecraft,
        sounds && type == WeatherType.HEAT
            ? state.intensity() * exposure * vegetation * middayQuiet(level)
            : 0.0F);

    if (AtmosphereConfig.WEATHER_PARTICLES.get()
        && minecraft.options.particles().get() != ParticleStatus.MINIMAL) {
      spawnParticles(level, player, weather.windAngle());
    }
  }

  public static void loggingOut() {
    WeatherSyncPayload.reset();
    rainLevel = -1.0F;
    thunderLevel = -1.0F;
    state = WeatherState.CLEAR;
  }

  public static void extract(LevelRenderState state, DeltaTracker deltaTracker, Camera camera) {
    List<WeatherEffectRenderer.ColumnInstance> snow = state.weatherRenderState.snowColumns;
    if (snow.isEmpty()) {
      return;
    }
    float partial = deltaTracker.getGameTimeDeltaPartialTick(false);
    double phaseX = driftX + driftSpeedX * partial;
    double phaseZ = driftZ + driftSpeedZ * partial;
    Vec3 cameraPos = camera.position();
    int cameraX = Mth.floor(cameraPos.x);
    int cameraZ = Mth.floor(cameraPos.z);
    for (int i = 0; i < snow.size(); i++) {
      WeatherEffectRenderer.ColumnInstance column = snow.get(i);

      int dx = column.x() - cameraX;
      int dz = column.z() - cameraZ;
      double length = Math.sqrt(dx * dx + dz * dz);
      if (length == 0.0) {
        continue;
      }
      double along = (phaseX * -dz + phaseZ * dx) / length;
      snow.set(
          i,
          new WeatherEffectRenderer.ColumnInstance(
              column.x(),
              column.z(),
              column.bottomY(),
              column.topY(),
              column.uOffset() - (float) (along % 1.0),
              column.vOffset(),
              column.lightCoords()));
    }
  }

  public static SoundInstance playSound(SoundInstance sound) {
    if (sound == null || !AtmosphereConfig.AMBIENT_SOUNDS.get()) {
      return sound;
    }
    boolean above = sound.getIdentifier().equals(SoundEvents.WEATHER_RAIN_ABOVE.location());
    if (!above && !sound.getIdentifier().equals(SoundEvents.WEATHER_RAIN.location())) {
      return sound;
    }
    Supplier<SoundEvent> variant =
        switch (state.type()) {
          case DRIZZLE -> GuestAtmosphere.DRIZZLE_SOUND;
          case DOWNPOUR, THUNDERSTORM -> GuestAtmosphere.DOWNPOUR_SOUND;
          default -> null;
        };
    if (variant == null) {
      return sound;
    }
    return new SimpleSoundInstance(
        variant.get(),
        SoundSource.WEATHER,
        above ? 0.1F : 0.2F,
        above ? 0.5F : 1.0F,
        SoundInstance.createUnseededRandom(),
        sound.getX(),
        sound.getY(),
        sound.getZ());
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

  private static void probeSurroundings(ClientLevel level, LocalPlayer player, float wind) {
    RandomSource random = level.getRandom();
    BlockPos origin = player.blockPosition();
    BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    int plants = 0;
    BlockPos log = null;
    for (int i = 0; i < SOUND_PROBES; i++) {
      probe.set(
          origin.getX() + random.nextInt(2 * PROBE_RADIUS + 1) - PROBE_RADIUS,
          origin.getY() + random.nextInt(PROBE_RADIUS + 1) - 4,
          origin.getZ() + random.nextInt(2 * PROBE_RADIUS + 1) - PROBE_RADIUS);
      BlockState block = level.getBlockState(probe);
      if (block.is(BlockTags.LEAVES)
          || block.is(BlockTags.FLOWERS)
          || block.is(BlockTags.REPLACEABLE_BY_TREES)) {
        plants++;
      } else if (log == null && block.is(BlockTags.LOGS)) {
        log = probe.immutable();
      }
    }
    vegetation = approach(vegetation, Math.min(1.0F, plants * 4.0F / SOUND_PROBES), 0.02F);

    creakCooldown--;
    if (log != null
        && wind > CREAK_WIND
        && creakCooldown <= 0
        && random.nextFloat() < (wind - CREAK_WIND) * 0.6F) {
      level.playLocalSound(
          log,
          GuestAtmosphere.TREE_CREAK_SOUND.get(),
          SoundSource.AMBIENT,
          0.4F + 0.6F * wind,
          0.8F + 0.3F * random.nextFloat(),
          false);
      creakCooldown = 30 + random.nextInt(60);
    }

    if (wind > BELL_WIND && level.getGameTime() % 20 == 0) {
      ChunkPos center = player.chunkPosition();
      for (int dx = -2; dx <= 2; dx++) {
        for (int dz = -2; dz <= 2; dz++) {
          for (BlockEntity entity :
              level.getChunk(center.x() + dx, center.z() + dz).getBlockEntities().values()) {
            if (entity instanceof BellBlockEntity
                && random.nextFloat() < (wind - BELL_WIND) * 0.8F) {
              level.playLocalSound(
                  entity.getBlockPos(),
                  GuestAtmosphere.BELL_RUSTLE_SOUND.get(),
                  SoundSource.AMBIENT,
                  0.3F + 0.9F * wind,
                  0.9F + 0.2F * random.nextFloat(),
                  false);
            }
          }
        }
      }
    }
  }

  private static float middayQuiet(ClientLevel level) {
    double fromNoon = Math.abs(GuestTime.tickOfDay(GuestTime.gameTime(level)) - 6_000L) / 2_000.0;
    return fromNoon < 1.0 ? (float) (0.5 + 0.5 * fromNoon) : 1.0F;
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

  private static float approach(float value, float target, float step) {
    return value + Mth.clamp(target - value, -step, step);
  }

  private static double approach(double value, double target, double step) {
    return value + Mth.clamp(target - value, -step, step);
  }

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
