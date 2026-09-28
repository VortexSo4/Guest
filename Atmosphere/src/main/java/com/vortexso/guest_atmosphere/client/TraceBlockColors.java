package com.vortexso.guest_atmosphere.client;

import com.vortexso.guest_atmosphere.GuestAtmosphere;
import com.vortexso.guest_atmosphere.block.AtmosphereBlocks;
import java.util.List;
import net.minecraft.client.color.block.BlockTintSources;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

@EventBusSubscriber(modid = GuestAtmosphere.MODID, value = Dist.CLIENT)
public final class TraceBlockColors {
  private TraceBlockColors() {}

  @SubscribeEvent
  public static void onBlockTints(RegisterColorHandlersEvent.BlockTintSources event) {
    event.register(List.of(BlockTintSources.grass()), AtmosphereBlocks.SNOWY_PLANT.get());
    event.register(List.of(BlockTintSources.foliage()), AtmosphereBlocks.IVY.get());
  }
}
