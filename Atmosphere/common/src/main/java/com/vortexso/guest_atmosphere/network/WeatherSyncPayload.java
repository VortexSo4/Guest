package com.vortexso.guest_atmosphere.network;

import com.vortexso.guest_atmosphere.GuestAtmosphere;
import com.vortexso.guest_atmosphere.weather.WeatherModel.Cover;
import com.vortexso.guest_core.api.world.WeatherState;
import com.vortexso.guest_core.api.world.WeatherType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record WeatherSyncPayload(
    WeatherState state, float temperature, float windAngle, Cover cover, boolean driving)
    implements CustomPacketPayload {
  public static final Type<WeatherSyncPayload> TYPE =
      new Type<>(Identifier.fromNamespaceAndPath(GuestAtmosphere.MODID, "weather"));

  public static final StreamCodec<FriendlyByteBuf, WeatherSyncPayload> CODEC =
      CustomPacketPayload.codec(WeatherSyncPayload::write, WeatherSyncPayload::read);

  public static final WeatherSyncPayload NONE =
      new WeatherSyncPayload(WeatherState.CLEAR, 0.5F, 0.0F, Cover.NONE, false);

  private static volatile WeatherSyncPayload latest = NONE;

  public static WeatherSyncPayload latest() {
    return latest;
  }

  public static void reset() {
    latest = NONE;
  }

  public static void handle(WeatherSyncPayload payload) {
    latest = payload;
  }

  private static WeatherSyncPayload read(FriendlyByteBuf buf) {
    WeatherType[] types = WeatherType.values();
    int ordinal = buf.readVarInt();
    WeatherType type = ordinal >= 0 && ordinal < types.length ? types[ordinal] : WeatherType.CLEAR;
    WeatherState state =
        new WeatherState(type, buf.readFloat(), buf.readFloat(), buf.readBoolean());
    return new WeatherSyncPayload(
        state,
        buf.readFloat(),
        buf.readFloat(),
        new Cover(buf.readFloat(), buf.readFloat()),
        buf.readBoolean());
  }

  private void write(FriendlyByteBuf buf) {
    buf.writeVarInt(state.type().ordinal());
    buf.writeFloat(state.intensity());
    buf.writeFloat(state.wind());
    buf.writeBoolean(state.aurora());
    buf.writeFloat(temperature);
    buf.writeFloat(windAngle);
    buf.writeFloat(cover.snow());
    buf.writeFloat(cover.dryness());
    buf.writeBoolean(driving);
  }

  @Override
  public Type<? extends CustomPacketPayload> type() {
    return TYPE;
  }
}
