package com.vortexso.guest_hands.client;

import com.vortexso.guest_hands.GuestHands;
import com.vortexso.guest_hands.HandsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Client half of the slow night: after vanilla's fade to black the world's sounds keep playing for
 * a moment, then sink to a muffled level until the player wakes. Uses the sound engine's per-source
 * gain (the same knob the music manager fades with), so already-playing sounds follow too.
 */
@EventBusSubscriber(modid = GuestHands.MODID, value = Dist.CLIENT)
public final class SleepClient {
  /** Night sounds stay at full volume this long after the screen is black. */
  private static final int NIGHT_SOUND_TICKS = 40;

  private static final int MUFFLE_RAMP_TICKS = 40;

  private static int sleptTicks;
  private static boolean muffled;

  private SleepClient() {}

  @SubscribeEvent
  static void tick(ClientTickEvent.Post event) {
    Minecraft minecraft = Minecraft.getInstance();
    LocalPlayer player = minecraft.player;
    if (player == null || !player.isSleeping() || !HandsConfig.SLEEP_ENABLED.get()) {
      sleptTicks = 0;
      if (muffled) {
        muffled = false;
        setGain(minecraft, 1.0F);
      }
      return;
    }
    sleptTicks++;
    float progress =
        Mth.clamp(
            (sleptTicks - Player.SLEEP_DURATION - NIGHT_SOUND_TICKS) / (float) MUFFLE_RAMP_TICKS,
            0.0F,
            1.0F);
    if (progress > 0.0F) {
      muffled = true;
      setGain(minecraft, Mth.lerp(progress, 1.0F, HandsConfig.MUFFLED_VOLUME.get().floatValue()));
    }
  }

  private static void setGain(Minecraft minecraft, float gain) {
    for (SoundSource source : SoundSource.values()) {
      if (source != SoundSource.MASTER && source != SoundSource.MUSIC && source != SoundSource.UI) {
        minecraft.getSoundManager().updateCategoryVolume(source, gain);
      }
    }
  }
}
