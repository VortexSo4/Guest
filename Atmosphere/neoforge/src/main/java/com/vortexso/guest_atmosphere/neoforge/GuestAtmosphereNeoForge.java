package com.vortexso.guest_atmosphere.neoforge;

import com.vortexso.guest_atmosphere.GuestAtmosphere;
import com.vortexso.guest_atmosphere.block.AtmosphereBlocks;
import com.vortexso.guest_atmosphere.network.WeatherSyncPayload;
import com.vortexso.guest_atmosphere.trace.TraceInteractions;
import com.vortexso.guest_atmosphere.weather.AtmosphereWeather;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.ItemAbility;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@Mod(GuestAtmosphere.MODID)
public final class GuestAtmosphereNeoForge {
  public GuestAtmosphereNeoForge(IEventBus modBus) {
    GuestAtmosphere.init();
    modBus.addListener(GuestAtmosphereNeoForge::onRegisterPayloads);
    modBus.addListener(GuestAtmosphereNeoForge::onCreativeTabs);
    NeoForge.EVENT_BUS.addListener(GuestAtmosphereNeoForge::onToolUse);
    NeoForge.EVENT_BUS.addListener(
        TagsUpdatedEvent.class, event -> AtmosphereWeather.clearBiomeCache());
  }

  private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
    event
        .registrar("2")
        .optional()
        .playToClient(
            WeatherSyncPayload.TYPE,
            WeatherSyncPayload.CODEC,
            (payload, context) -> WeatherSyncPayload.handle(payload));
  }

  private static void onCreativeTabs(BuildCreativeModeTabContentsEvent event) {
    AtmosphereBlocks.creativeTab(event.getTabKey(), event::accept);
  }

  private static void onToolUse(BlockEvent.BlockToolModificationEvent event) {
    ItemAbility ability = event.getItemAbility();
    TraceInteractions.Tool tool =
        ability == ItemAbilities.SHEARS_TRIM
            ? TraceInteractions.Tool.SHEARS
            : ability == ItemAbilities.HOE_TILL
                ? TraceInteractions.Tool.HOE
                : ability == ItemAbilities.SHOVEL_FLATTEN ? TraceInteractions.Tool.SHOVEL : null;
    if (tool == null) {
      return;
    }
    BlockState result =
        TraceInteractions.toolModified(
            tool,
            event.getContext().getLevel(),
            event.getPos(),
            event.getState(),
            event.isSimulated());
    if (result != null) {
      event.setFinalState(result);
    }
  }
}
