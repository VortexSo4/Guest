package com.vortexso.guest_core.platform;

import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

public interface Registrar<T> {
  <E extends T> Supplier<E> register(String name, Function<ResourceKey<T>, E> factory);

  static <B extends Block> Supplier<B> block(
      Registrar<Block> registrar,
      String name,
      Function<BlockBehaviour.Properties, B> constructor,
      UnaryOperator<BlockBehaviour.Properties> properties) {
    return registrar.register(
        name,
        key -> constructor.apply(properties.apply(BlockBehaviour.Properties.of()).setId(key)));
  }

  static <I extends Item> Supplier<I> item(
      Registrar<Item> registrar,
      String name,
      Function<Item.Properties, I> constructor,
      UnaryOperator<Item.Properties> properties) {
    return registrar.register(
        name, key -> constructor.apply(properties.apply(new Item.Properties()).setId(key)));
  }

  static Supplier<BlockItem> blockItem(
      Registrar<Item> registrar, String name, Supplier<? extends Block> block) {
    return registrar.register(
        name,
        key ->
            new BlockItem(
                block.get(), new Item.Properties().setId(key).useBlockDescriptionPrefix()));
  }
}
