package com.vortexso.guest_core.platform;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class Listeners<T> implements Iterable<T> {
  private final List<T> listeners = new CopyOnWriteArrayList<>();

  public void register(T listener) {
    listeners.add(listener);
  }

  public boolean isEmpty() {
    return listeners.isEmpty();
  }

  @Override
  public Iterator<T> iterator() {
    return listeners.iterator();
  }
}
