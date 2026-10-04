package com.vortexso.guest_core.neoforge;

import com.google.common.base.Suppliers;
import com.mojang.serialization.MapCodec;
import com.vortexso.guest_core.platform.Attachment;
import com.vortexso.guest_core.platform.Platform;
import com.vortexso.guest_core.platform.Registrar;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jspecify.annotations.Nullable;

public final class NeoForgePlatform implements Platform {
  private final Map<String, DeferredRegister<?>> registers = new ConcurrentHashMap<>();

  @Override
  public boolean isModLoaded(String modId) {
    return ModList.get().isLoaded(modId);
  }

  @Override
  public boolean isClient() {
    return FMLEnvironment.getDist().isClient();
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> Registrar<T> registrar(String modId, ResourceKey<? extends Registry<T>> registry) {
    DeferredRegister<T> deferred =
        (DeferredRegister<T>)
            registers.computeIfAbsent(
                modId + "|" + registry.identifier(),
                ignored -> {
                  DeferredRegister<T> created = DeferredRegister.create(registry, modId);
                  created.register(container(modId).getEventBus());
                  return created;
                });
    return new Registrar<>() {
      @Override
      public <E extends T> Supplier<E> register(String name, Function<ResourceKey<T>, E> factory) {
        Supplier<E> holder =
            deferred.register(
                name, id -> factory.apply(ResourceKey.create(deferred.getRegistryKey(), id)));
        return Suppliers.memoize(holder::get);
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
    Supplier<AttachmentType<T>> type =
        registrar(modId, NeoForgeRegistries.Keys.ATTACHMENT_TYPES)
            .register(
                name,
                key -> {
                  AttachmentType.Builder<T> builder = AttachmentType.builder(initial);
                  if (shouldSave == null) {
                    builder.serialize(codec);
                  } else {
                    builder.serialize(codec, shouldSave);
                  }
                  if (sync != null) {
                    builder.sync(sync);
                  }
                  return builder.build();
                });
    return new Attachment<>() {
      @Override
      public T get(Object holder) {
        return ((IAttachmentHolder) holder).getData(type);
      }

      @Override
      public @Nullable T getExisting(Object holder) {
        return ((IAttachmentHolder) holder).getExistingDataOrNull(type);
      }

      @Override
      public void set(Object holder, T value) {
        ((IAttachmentHolder) holder).setData(type, value);
      }

      @Override
      public boolean has(Object holder) {
        return ((IAttachmentHolder) holder).hasData(type);
      }
    };
  }

  @Override
  public void registerConfig(
      String modId, ConfigType type, ModConfigSpec spec, @Nullable Runnable onLoad) {
    ModContainer container = container(modId);
    container.registerConfig(ModConfig.Type.valueOf(type.name()), spec);
    if (onLoad != null) {
      container
          .getEventBus()
          .addListener(
              ModConfigEvent.Loading.class,
              event -> {
                if (event.getConfig().getSpec() == spec) {
                  onLoad.run();
                }
              });
      container
          .getEventBus()
          .addListener(
              ModConfigEvent.Reloading.class,
              event -> {
                if (event.getConfig().getSpec() == spec) {
                  onLoad.run();
                }
              });
    }
  }

  @Override
  public void registerConfigScreen(String modId) {
    container(modId).registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
  }

  private static ModContainer container(String modId) {
    return ModList.get()
        .getModContainerById(modId)
        .orElseThrow(() -> new IllegalStateException("Mod " + modId + " is not loaded"));
  }

  @Override
  public boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
    return player.connection.hasChannel(type);
  }
}
