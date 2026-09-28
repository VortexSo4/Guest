package com.vortexso.guest_architects.city;

import net.minecraft.network.chat.Component;

public enum ArchitectRole {
  RITUAL,
  MELODY,
  DEEPSLATE,
  WOOL,
  WATCHER,
  TEACHER,

  KEEPER,
  YOUNG;

  public Component displayName() {
    return Component.translatable(
        "guest_architects.role." + name().toLowerCase(java.util.Locale.ROOT));
  }
}
