package com.vortexso.guest_hands.client;

import com.vortexso.guest_hands.HandsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

public final class SleepClient {

  private static final int NIGHT_SOUND_TICKS = 40;

  private static final int MUFFLE_RAMP_TICKS = 40;

  private static int sleptTicks;
  private static boolean muffled;

  private SleepClient() {}

  static void tick() {
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
