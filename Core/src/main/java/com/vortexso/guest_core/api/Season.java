package com.vortexso.guest_core.api;

import java.util.Locale;
import net.minecraft.network.chat.Component;

public enum Season {
  SPRING,
  SUMMER,
  AUTUMN,
  WINTER;

  public Component displayName() {
    return Component.translatable("guest_core.season." + name().toLowerCase(Locale.ROOT));
  }
}
