package com.vortexso.guest_bench;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.vortexso.guest_core.platform.Events;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;

public final class GuestBench {
  private static final List<Bot> BOTS = new ArrayList<>();

  private GuestBench() {}

  public static void init() {
    Events.COMMANDS.register(GuestBench::commands);
    Events.SERVER_TICK.register(GuestBench::tick);
  }

  private static void commands(
      CommandDispatcher<CommandSourceStack> dispatcher,
      CommandBuildContext buildContext,
      Commands.CommandSelection selection) {
    dispatcher.register(
        Commands.literal("guestbench")
            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .then(
                Commands.literal("bot")
                    .then(
                        Commands.argument("name", StringArgumentType.word())
                            .then(
                                Commands.argument("x", DoubleArgumentType.doubleArg())
                                    .then(
                                        Commands.argument("z", DoubleArgumentType.doubleArg())
                                            .then(
                                                Commands.argument("dx", DoubleArgumentType.doubleArg())
                                                    .then(
                                                        Commands.argument(
                                                                "dz", DoubleArgumentType.doubleArg())
                                                            .executes(GuestBench::spawn)))))))
            .then(
                Commands.literal("halt")
                    .executes(
                        context -> {
                          BOTS.forEach(bot -> bot.dx = bot.dz = 0.0);
                          return BOTS.size();
                        })));
  }

  private static int spawn(CommandContext<CommandSourceStack> context) {
    MinecraftServer server = context.getSource().getServer();
    ServerLevel level = server.overworld();
    String name = StringArgumentType.getString(context, "name");
    GameProfile profile =
        new GameProfile(
            UUID.nameUUIDFromBytes(("guest_bench:" + name).getBytes(StandardCharsets.UTF_8)), name);
    ServerPlayer player =
        new ServerPlayer(server, level, profile, ClientInformation.createDefault());
    Connection connection = new Connection(PacketFlow.SERVERBOUND);
    new EmbeddedChannel(new Discard(), connection);
    server
        .getPlayerList()
        .placeNewPlayer(
            connection,
            player,
            new CommonListenerCookie(profile, 0, ClientInformation.createDefault(), false));
    player.setGameMode(GameType.CREATIVE);
    player.getAbilities().flying = true;
    Bot bot =
        new Bot(
            player,
            DoubleArgumentType.getDouble(context, "x"),
            DoubleArgumentType.getDouble(context, "z"),
            DoubleArgumentType.getDouble(context, "dx"),
            DoubleArgumentType.getDouble(context, "dz"));
    BOTS.add(bot);
    bot.move(level);
    context.getSource().sendSuccess(() -> Component.literal("Bot " + name + " joined"), false);
    return 1;
  }

  private static void tick(MinecraftServer server) {
    ServerLevel level = server.overworld();
    for (Bot bot : BOTS) {
      bot.x += bot.dx;
      bot.z += bot.dz;
      bot.move(level);
      bot.player.connection.chunkSender.onChunkBatchReceivedByClient(64.0F);
    }
  }

  private static final class Bot {
    final ServerPlayer player;
    double x;
    double z;
    double dx;
    double dz;

    Bot(ServerPlayer player, double x, double z, double dx, double dz) {
      this.player = player;
      this.x = x;
      this.z = z;
      this.dx = dx;
      this.dz = dz;
    }

    void move(ServerLevel level) {
      double y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) x, (int) z) + 2.0;
      player.teleportTo(level, x, Math.max(y, level.getSeaLevel() + 2.0), z, Set.of(), 0, 30, false);
      level.getChunkSource().move(player);
    }
  }

  private static final class Discard extends ChannelOutboundHandlerAdapter {
    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
      ReferenceCountUtil.release(message);
      promise.setSuccess();
    }

    @Override
    public void flush(ChannelHandlerContext context) {}
  }
}
