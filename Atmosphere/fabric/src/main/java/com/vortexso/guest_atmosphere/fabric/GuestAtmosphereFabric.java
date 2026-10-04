package com.vortexso.guest_atmosphere.fabric;

import com.vortexso.guest_atmosphere.GuestAtmosphere;
import com.vortexso.guest_atmosphere.block.AtmosphereBlocks;
import com.vortexso.guest_atmosphere.network.WeatherSyncPayload;
import com.vortexso.guest_atmosphere.trace.TraceInteractions;
import com.vortexso.guest_atmosphere.weather.AtmosphereWeather;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.registries.BuiltInRegistries;

public final class GuestAtmosphereFabric implements ModInitializer {
  @Override
  public void onInitialize() {
    GuestAtmosphere.init();
    PayloadTypeRegistry.clientboundPlay()
        .register(WeatherSyncPayload.TYPE, WeatherSyncPayload.CODEC);
    CreativeModeTabEvents.MODIFY_OUTPUT_ALL.register(
        (tab, output) ->
            BuiltInRegistries.CREATIVE_MODE_TAB
                .getResourceKey(tab)
                .ifPresent(key -> AtmosphereBlocks.creativeTab(key, output::accept)));
    UseBlockCallback.EVENT.register(TraceInteractions::useTool);
    CommonLifecycleEvents.TAGS_LOADED.register(
        (registries, client) -> AtmosphereWeather.clearBiomeCache());
  }
}
