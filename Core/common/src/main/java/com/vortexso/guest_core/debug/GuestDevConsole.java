package com.vortexso.guest_core.debug;

import com.vortexso.guest_core.GuestCore;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;

public final class GuestDevConsole {
  private static final String PROPERTY = "guest.devConsole";
  private static final int POLL_TICKS = 10;

  private static volatile Consumer<String> clientActions;

  private static final Queue<String> pending = new ArrayDeque<>();
  private static long readOffset = -1;
  private static int waitTicks;
  private static int pollCountdown;

  private GuestDevConsole() {}

  public static void setClientActions(Consumer<String> actions) {
    clientActions = actions;
  }

  public static void tick(MinecraftServer server) {
    String path = System.getProperty(PROPERTY);
    if (path == null || path.isBlank()) {
      return;
    }
    if (--pollCountdown <= 0) {
      pollCountdown = POLL_TICKS;
      readNewLines(path);
    }
    if (waitTicks > 0) {
      waitTicks--;
      return;
    }
    while (!pending.isEmpty() && waitTicks == 0) {
      run(server, pending.poll().strip());
    }
  }

  private static void run(MinecraftServer server, String line) {
    if (line.isEmpty() || line.startsWith("#")) {
      return;
    }
    GuestCore.LOGGER.info("[GuestDevConsole] > {}", line);
    if (line.startsWith("!wait ")) {
      waitTicks = Math.max(0, Integer.parseInt(line.substring(6).strip()));
      return;
    }
    if (line.startsWith("!")) {
      Consumer<String> actions = clientActions;
      if (actions == null) {
        GuestCore.LOGGER.warn("[GuestDevConsole] client action ignored (no client): {}", line);
      } else {
        actions.accept(line.substring(1));
      }
      return;
    }
    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), line);
  }

  private static void readNewLines(String path) {
    try (RandomAccessFile file = new RandomAccessFile(path, "r")) {
      if (readOffset < 0) {

        readOffset = 0;
      }
      if (file.length() < readOffset) {
        readOffset = 0;
      }
      file.seek(readOffset);
      byte[] bytes = new byte[(int) (file.length() - readOffset)];
      file.readFully(bytes);
      String text = new String(bytes, StandardCharsets.UTF_8);
      int lastNewline = text.lastIndexOf('\n');
      if (lastNewline < 0) {
        return;
      }
      readOffset += text.substring(0, lastNewline + 1).getBytes(StandardCharsets.UTF_8).length;
      for (String line : text.substring(0, lastNewline).split("\n")) {
        pending.add(line);
      }
    } catch (IOException ignored) {

    }
  }
}
