package com.vortexso.guest_architects.fabric;

import com.vortexso.guest_architects.ArchitectsEvents;
import com.vortexso.guest_architects.GuestArchitects;
import com.vortexso.guest_architects.block.ArchitectPortalBlockEntity;
import com.vortexso.guest_architects.entity.Architect;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

public final class GuestArchitectsFabric implements ModInitializer {
  @Override
  public void onInitialize() {
    GuestArchitects.init(
        block ->
            FabricBlockEntityTypeBuilder.create(ArchitectPortalBlockEntity::new, block).build());
    FabricDefaultAttributeRegistry.register(
        GuestArchitects.ARCHITECT.get(), Architect.createAttributes());
    ServerLifecycleEvents.SERVER_STARTING.register(ArchitectsEvents::onServerAboutToStart);
    UseItemCallback.EVENT.register((player, level, hand) -> held(player));
    AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> held(player));
    AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> held(player));
    UseEntityCallback.EVENT.register(
        (player, level, hand, entity, hit) ->
            ArchitectsEvents.interactEntity(player, entity, player.getItemInHand(hand)));
  }

  private static InteractionResult held(Player player) {
    return ArchitectsEvents.isHeld(player) ? InteractionResult.FAIL : InteractionResult.PASS;
  }
}
