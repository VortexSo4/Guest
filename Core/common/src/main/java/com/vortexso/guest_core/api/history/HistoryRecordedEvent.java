package com.vortexso.guest_core.api.history;

import com.vortexso.guest_core.platform.Listeners;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;

public final class HistoryRecordedEvent {
  public static final Listeners<Consumer<HistoryRecordedEvent>> LISTENERS = new Listeners<>();

  private final ServerLevel level;
  private final HistoryRecord record;

  public HistoryRecordedEvent(ServerLevel level, HistoryRecord record) {
    this.level = level;
    this.record = record;
  }

  public ServerLevel level() {
    return level;
  }

  public HistoryRecord record() {
    return record;
  }

  public static void post(HistoryRecordedEvent event) {
    for (Consumer<HistoryRecordedEvent> listener : LISTENERS) {
      listener.accept(event);
    }
  }
}
