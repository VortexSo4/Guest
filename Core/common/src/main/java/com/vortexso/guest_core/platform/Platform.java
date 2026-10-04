package com.vortexso.guest_core.platform;

import com.mojang.serialization.MapCodec;
import java.util.ServiceLoader;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jspecify.annotations.Nullable;

public interface Platform {
  Platform INSTANCE =
      ServiceLoader.load(Platform.class, Platform.class.getClassLoader())
          .findFirst()
          .orElseThrow(() -> new IllegalStateException("No Guest platform implementation found"));

  boolean isModLoaded(String modId);

  boolean isClient();

  <T> Registrar<T> registrar(String modId, ResourceKey<? extends Registry<T>> registry);

  <T> Attachment<T> attachment(
      String modId,
      String name,
      Supplier<T> initial,
      MapCodec<T> codec,
      @Nullable Predicate<T> shouldSave,
      @Nullable StreamCodec<? super RegistryFriendlyByteBuf, T> sync);

  void registerConfig(String modId, ConfigType type, ModConfigSpec spec, @Nullable Runnable onLoad);

  void registerConfigScreen(String modId);

  boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type);

  enum ConfigType {
    COMMON,
    SERVER,
    CLIENT
  }
}
