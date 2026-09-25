package com.vortexso.guest_architects.city;

import net.minecraft.network.chat.Component;

/** Visible task of an Architect. Fixed by identity index, so the same individual keeps it. */
public enum ArchitectRole {
  RITUAL,
  MELODY,
  DEEPSLATE,
  WOOL,
  WATCHER,
  TEACHER,
  YOUNG;

  public Component displayName() {
    return Component.translatable(
        "guest_architects.role." + name().toLowerCase(java.util.Locale.ROOT));
  }
}
