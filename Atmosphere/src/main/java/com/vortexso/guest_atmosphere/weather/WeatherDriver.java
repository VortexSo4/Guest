package com.vortexso.guest_atmosphere.weather;

import com.vortexso.guest_atmosphere.AtmosphereConfig;
import com.vortexso.guest_atmosphere.GuestAtmosphere;
import com.vortexso.guest_atmosphere.debug.AtmosphereDebug;
import com.vortexso.guest_atmosphere.network.WeatherSyncPayload;
import com.vortexso.guest_atmosphere.weather.WeatherModel.Sample;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.world.WeatherType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.WeatherData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Makes vanilla follow the model. Clients get their own region's state (rendering, rain level,
 * sounds); the global {@link WeatherData} only carries what vanilla mechanics need globally — it
 * rains if any online player's region has precipitation, thunders if any has a thunderstorm. Where
 * exactly it rains is answered per position by the {@code Level.precipitationAt} mixin.
 */
@EventBusSubscriber(modid = GuestAtmosphere.MODID)
public final class WeatherDriver {
  private static final int SYNC_INTERVAL = 20;

  /** Longer than the sync interval so vanilla's own countdown never flips the weather. */
  private static final int HELD_WEATHER_TICKS = 1_200;

  private static final Identifier SLOWDOWN_ID =
      Identifier.fromNamespaceAndPath(GuestAtmosphere.MODID, "weather_slowdown");

  private static final Item[] WARM_CLOTHING = {
    Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS
  };
  private static final EquipmentSlot[] ARMOR_SLOTS = {
    EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
  };

  private static boolean raining;
  private static boolean thundering;

  private WeatherDriver() {}

  @SubscribeEvent
  public static void onServerTick(ServerTickEvent.Pre event) {
    ServerLevel overworld = event.getServer().overworld();
    boolean driving = AtmosphereConfig.DRIVE_VANILLA_WEATHER.get();

    if (event.getServer().getTickCount() % SYNC_INTERVAL == 0) {
      long time = GuestTime.gameTime(overworld);
      boolean anyRain = false;
      boolean anyThunder = false;
      ServerPlayer observed = null;
      for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
        if (player.level() != overworld) {
          PacketDistributor.sendToPlayer(player, WeatherSyncPayload.NONE);
          removeSlowdown(player);
          continue;
        }
        Sample sample = AtmosphereWeather.sample(overworld, player.blockPosition(), time);
        WeatherType type = sample.state().type();
        anyRain |= type.isPrecipitation();
        anyThunder |= type == WeatherType.THUNDERSTORM;
        PacketDistributor.sendToPlayer(
            player,
            new WeatherSyncPayload(
                sample.state(), sample.temperature(), sample.windAngle(), driving));
        applySlowdown(player, sample);
        if (observed == null) {
          observed = player;
        }
      }
      if (observed != null) {
        AtmosphereDebug.capture(overworld, observed);
      }
      raining = anyRain;
      thundering = anyThunder;
    }

    if (driving) {
      WeatherData data = overworld.getWeatherData();
      data.setClearWeatherTime(0);
      data.setRaining(raining);
      data.setRainTime(HELD_WEATHER_TICKS);
      data.setThundering(thundering);
      data.setThunderTime(HELD_WEATHER_TICKS);
    }
  }

  @SubscribeEvent
  public static void onTagsUpdated(TagsUpdatedEvent event) {
    AtmosphereWeather.clearBiomeCache();
  }

  /** Cold storms slow people outside; no damage. Leather clothing and shelter cancel it. */
  private static void applySlowdown(ServerPlayer player, Sample sample) {
    AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
    if (speed == null) {
      return;
    }
    WeatherType type = sample.state().type();
    double slowdown =
        switch (type) {
          case BLIZZARD -> AtmosphereConfig.BLIZZARD_SLOWDOWN.get();
          case SNOWSTORM -> AtmosphereConfig.SNOWSTORM_SLOWDOWN.get();
          default -> 0.0;
        };
    if (!AtmosphereConfig.SLOWDOWN_ENABLED.get()
        || player.isCreative()
        || player.isSpectator()
        || !isExposed(player.level(), BlockPos.containing(player.getEyePosition()))) {
      slowdown = 0.0;
    }
    slowdown *= sample.state().intensity();
    slowdown *= 1.0 - warmth(player) * AtmosphereConfig.WARM_CLOTHING_MITIGATION.get();
    if (slowdown > 0.005) {
      speed.addOrUpdateTransientModifier(
          new AttributeModifier(
              SLOWDOWN_ID, -slowdown, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    } else {
      speed.removeModifier(SLOWDOWN_ID);
    }
  }

  private static void removeSlowdown(ServerPlayer player) {
    AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
    if (speed != null) {
      speed.removeModifier(SLOWDOWN_ID);
    }
  }

  /** 0..1 share of leather armour worn. */
  private static double warmth(ServerPlayer player) {
    int pieces = 0;
    for (int i = 0; i < ARMOR_SLOTS.length; i++) {
      ItemStack stack = player.getItemBySlot(ARMOR_SLOTS[i]);
      if (stack.is(WARM_CLOTHING[i])) {
        pieces++;
      }
    }
    return pieces / (double) ARMOR_SLOTS.length;
  }

  /** Surface weather applies only where the sky is visible; caves and houses are sheltered. */
  public static boolean isExposed(Level level, BlockPos pos) {
    return level.canSeeSky(pos);
  }
}
