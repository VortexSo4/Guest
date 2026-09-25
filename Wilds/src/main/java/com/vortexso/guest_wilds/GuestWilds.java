package com.vortexso.guest_wilds;

import com.mojang.logging.LogUtils;
import com.vortexso.guest_core.api.world.GuestWildlife;
import com.vortexso.guest_core.debug.GuestDebug;
import com.vortexso.guest_wilds.lair.Lairs;
import java.util.function.Supplier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.slf4j.Logger;

@Mod(GuestWilds.MODID)
public class GuestWilds {
  public static final String MODID = "guest_wilds";
  public static final Logger LOGGER = LogUtils.getLogger();

  public static final String DEBUG_LAIRS = "wilds_lairs";
  public static final String DEBUG_PATHS = "wilds_paths";
  public static final String DEBUG_HERDS = "wilds_herds";
  public static final String DEBUG_FISH = "wilds_fish";

  private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
      DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MODID);

  public static final Supplier<AttachmentType<Membership>> MEMBERSHIP =
      ATTACHMENTS.register(
          "membership",
          () ->
              AttachmentType.builder(() -> Membership.NONE)
                  .serialize(Membership.MAP_CODEC)
                  .build());

  public GuestWilds(IEventBus modBus, ModContainer container) {
    ATTACHMENTS.register(modBus);
    container.registerConfig(ModConfig.Type.SERVER, WildsConfig.SPEC);
    GuestDebug.register(DEBUG_LAIRS);
    GuestDebug.register(DEBUG_PATHS);
    GuestDebug.register(DEBUG_HERDS);
    GuestDebug.register(DEBUG_FISH);
    GuestWildlife.registerHostilePressure(Lairs::hostilePressure);
  }
}
