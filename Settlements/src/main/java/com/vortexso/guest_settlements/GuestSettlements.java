package com.vortexso.guest_settlements;

import com.mojang.logging.LogUtils;
import com.vortexso.guest_core.debug.GuestDebug;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(GuestSettlements.MODID)
public class GuestSettlements {
  public static final String MODID = "guest_settlements";
  public static final Logger LOGGER = LogUtils.getLogger();

  public static final String DEBUG_CHANNEL = "settlements";

  public static final TagKey<Block> CLEARABLE_COVER = blocks("clearable_cover");
  public static final TagKey<Block> VILLAGER_DIGGABLE = blocks("villager_diggable");

  public GuestSettlements(IEventBus modBus, ModContainer container) {
    container.registerConfig(ModConfig.Type.SERVER, SettlementsConfig.SPEC);
    GuestDebug.register(DEBUG_CHANNEL);
  }

  public static Identifier id(String path) {
    return Identifier.fromNamespaceAndPath(MODID, path);
  }

  private static TagKey<Block> blocks(String path) {
    return TagKey.create(Registries.BLOCK, id(path));
  }
}
