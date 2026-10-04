package com.vortexso.guest_core.fabric;

import com.mojang.serialization.MapCodec;
import com.vortexso.guest_core.platform.Attachment;
import com.vortexso.guest_core.platform.Platform;
import com.vortexso.guest_core.platform.Registrar;
import fuzs.forgeconfigapiport.fabric.api.v5.ConfigRegistry;
import fuzs.forgeconfigapiport.fabric.api.v5.ModConfigEvents;
import fuzs.forgeconfigapiport.fabric.api.v5.client.ConfigScreenFactoryRegistry;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jspecify.annotations.Nullable;

public final class FabricPlatform implements Platform {
  @Override
  public boolean isModLoaded(String modId) {
    return FabricLoader.getInstance().isModLoaded(modId);
  }

  @Override
  public boolean isClient() {
    return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> Registrar<T> registrar(String modId, ResourceKey<? extends Registry<T>> registry) {
    Registry<T> target =
        (Registry<T>)
            BuiltInRegistries.REGISTRY
                .getOptional(registry.identifier())
                .orElseThrow(() -> new IllegalStateException("Unknown registry " + registry));
    return new Registrar<>() {
      @Override
      public <E extends T> Supplier<E> register(String name, Function<ResourceKey<T>, E> factory) {
        ResourceKey<T> key =
            ResourceKey.create(target.key(), Identifier.fromNamespaceAndPath(modId, name));
        E value = Registry.register(target, key, factory.apply(key));
        return () -> value;
      }
    };
  }

  @Override
  public <T> Attachment<T> attachment(
      String modId,
      String name,
      Supplier<T> initial,
      MapCodec<T> codec,
      @Nullable Predicate<T> shouldSave,
      @Nullable StreamCodec<? super RegistryFriendlyByteBuf, T> sync) {
    AttachmentType<T> type =
        AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(modId, name),
            builder -> {
              builder.initializer(initial).persistent(codec.codec());
              if (sync != null) {
                builder.syncWith(sync, AttachmentSyncPredicate.all());
              }
            });
    return new Attachment<>() {
      @Override
      public T get(Object holder) {
        return ((AttachmentTarget) holder).getAttachedOrCreate(type);
      }

      @Override
      public @Nullable T getExisting(Object holder) {
        return ((AttachmentTarget) holder).getAttached(type);
      }

      @Override
      public void set(Object holder, T value) {
        ((AttachmentTarget) holder).setAttached(type, value);
      }

      @Override
      public boolean has(Object holder) {
        return ((AttachmentTarget) holder).hasAttached(type);
      }
    };
  }

  @Override
  public void registerConfig(
      String modId, ConfigType type, ModConfigSpec spec, @Nullable Runnable onLoad) {
    if (onLoad != null) {
      ModConfigEvents.loading(modId)
          .register(
              config -> {
                if (config.getSpec() == spec) {
                  onLoad.run();
                }
              });
      ModConfigEvents.reloading(modId)
          .register(
              config -> {
                if (config.getSpec() == spec) {
                  onLoad.run();
                }
              });
    }
    ConfigRegistry.INSTANCE.register(modId, ModConfig.Type.valueOf(type.name()), spec);
  }

  @Override
  public void registerConfigScreen(String modId) {
    ConfigScreenFactoryRegistry.INSTANCE.register(modId, ConfigurationScreen::new);
  }

  @Override
  public boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
    return ServerPlayNetworking.canSend(player, type);
  }
}
