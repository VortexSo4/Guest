package com.vortexso.guest_core.platform;

import org.jspecify.annotations.Nullable;

public interface Attachment<T> {
  T get(Object holder);

  @Nullable T getExisting(Object holder);

  void set(Object holder, T value);

  boolean has(Object holder);
}
