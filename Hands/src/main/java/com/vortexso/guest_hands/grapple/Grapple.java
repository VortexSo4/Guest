package com.vortexso.guest_hands.grapple;

import com.vortexso.guest_hands.GuestHands;
import com.vortexso.guest_hands.HandsConfig;
import com.vortexso.guest_hands.client.GrappleClient;
import io.netty.buffer.ByteBuf;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jspecify.annotations.Nullable;

/**
 * Pickaxe grappling, shared and server side. Motion is client-authoritative (like all player
 * movement), so the client moves itself and only reports attach/detach; the server re-validates the
 * anchor, measures the distance actually travelled for durability and clears fall distance while
 * the pickaxe carries the player.
 */
@EventBusSubscriber(modid = GuestHands.MODID)
public final class Grapple {
  /** Extra slack for latency between the client's anchor choice and the server's position. */
  private static final double SERVER_REACH_TOLERANCE = 1.5;

  /** Larger per-tick jumps are teleports, not distance carried by the pickaxe. */
  private static final double TELEPORT_DISTANCE = 4.0;

  private static final Map<UUID, Attachment> ATTACHED = new HashMap<>();

  private Grapple() {}

  public record Anchor(BlockPos pos, Direction face) {}

  /** Server view of one attached player; read by the singleplayer debug renderer. */
  public static final class Attachment {
    public BlockPos anchor;
    Vec3 lastPosition;
    final GrappleLoad load = new GrappleLoad();
    double soundDistance;
    public double carried;

    Attachment(BlockPos anchor, Vec3 position) {
      this.anchor = anchor;
      this.lastPosition = position;
    }
  }

  public static @Nullable Attachment attachment(Player player) {
    return ATTACHED.get(player.getUUID());
  }

  public static boolean holdsPickaxe(Player player) {
    return player.getMainHandItem().is(ItemTags.PICKAXES);
  }

  /**
   * A block the pickaxe can bite into and hang from: pickaxe-mineable, a sturdy face toward the
   * player, and not something that would itself fall or tear (falling blocks, leaves, fluids).
   */
  public static boolean isValidAnchor(BlockGetter level, BlockPos pos, Direction face) {
    BlockState state = level.getBlockState(pos);
    return state.is(BlockTags.MINEABLE_WITH_PICKAXE)
        && !state.is(BlockTags.LEAVES)
        && !(state.getBlock() instanceof Fallable)
        && state.getFluidState().isEmpty()
        && state.isFaceSturdy(level, pos, face);
  }

  /** Horizontal gap between the body and a block column. */
  public static double gap(AABB body, BlockPos pos) {
    double dx = Math.max(0.0, Math.max(pos.getX() - body.maxX, body.minX - (pos.getX() + 1.0)));
    double dz = Math.max(0.0, Math.max(pos.getZ() - body.maxZ, body.minZ - (pos.getZ() + 1.0)));
    return Math.sqrt(dx * dx + dz * dz);
  }

  /** Nearest valid wall block beside the body; the preferred block wins while still valid. */
  public static @Nullable Anchor findAnchor(
      Level level, Player player, double reach, @Nullable BlockPos preferred) {
    AABB body = player.getBoundingBox();
    BlockPos min = BlockPos.containing(body.minX - reach, body.minY, body.minZ - reach);
    BlockPos max = BlockPos.containing(body.maxX + reach, body.maxY - 1.0E-3, body.maxZ + reach);
    Anchor best = null;
    double bestScore = Double.MAX_VALUE;
    for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
      if (!besideBody(body, pos)) {
        continue;
      }
      double gap = gap(body, pos);
      if (gap > reach) {
        continue;
      }
      Direction face = faceToward(pos, player.position());
      if (!isValidAnchor(level, pos, face)) {
        continue;
      }
      double score = pos.equals(preferred) ? -1.0 : gap;
      if (score < bestScore) {
        bestScore = score;
        best = new Anchor(pos.immutable(), face);
      }
    }
    return best;
  }

  /** Walls only: blocks directly above/below the body column are floors and ceilings. */
  private static boolean besideBody(AABB body, BlockPos pos) {
    return pos.getX() >= body.maxX
        || pos.getX() + 1.0 <= body.minX
        || pos.getZ() >= body.maxZ
        || pos.getZ() + 1.0 <= body.minZ;
  }

  /** The horizontal block face that looks at the player. */
  public static Direction faceToward(BlockPos pos, Vec3 player) {
    double dx = player.x - (pos.getX() + 0.5);
    double dz = player.z - (pos.getZ() + 0.5);
    return Math.abs(dx) > Math.abs(dz)
        ? (dx > 0 ? Direction.EAST : Direction.WEST)
        : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
  }

  @SubscribeEvent
  static void registerPayloads(RegisterPayloadHandlersEvent event) {
    event
        .registrar("1")
        .playBidirectional(
            GrapplePayload.TYPE,
            GrapplePayload.CODEC,
            Grapple::handleFromClient,
            (payload, context) -> GrappleClient.onServerDetach());
  }

  private static void handleFromClient(GrapplePayload payload, IPayloadContext context) {
    if (!(context.player() instanceof ServerPlayer player)) {
      return;
    }
    if (!payload.attached()) {
      ATTACHED.remove(player.getUUID());
      return;
    }
    ServerLevel level = player.level();
    BlockPos anchor = payload.anchor();
    boolean valid =
        HandsConfig.GRAPPLE_ENABLED.get()
            && holdsPickaxe(player)
            && level.isLoaded(anchor)
            && gap(player.getBoundingBox(), anchor)
                <= HandsConfig.GRAPPLE_REACH.get() + SERVER_REACH_TOLERANCE
            && isValidAnchor(level, anchor, faceToward(anchor, player.position()));
    if (!valid) {
      detach(player);
      return;
    }
    Attachment existing = ATTACHED.get(player.getUUID());
    if (existing != null) {
      existing.anchor = anchor;
      return;
    }
    Attachment attachment = new Attachment(anchor, player.position());
    ATTACHED.put(player.getUUID(), attachment);
    // A caught fall is carried by the same rule as climbing: the height already fallen is paid now.
    applyLoad(player, attachment, player.fallDistance);
    player.resetFallDistance();
    playSound(level, anchor, player, true);
  }

  private static void detach(ServerPlayer player) {
    ATTACHED.remove(player.getUUID());
    PacketDistributor.sendToPlayer(player, new GrapplePayload(false, BlockPos.ZERO));
  }

  @SubscribeEvent
  static void tick(PlayerTickEvent.Post event) {
    if (!(event.getEntity() instanceof ServerPlayer player)) {
      return;
    }
    Attachment attachment = ATTACHED.get(player.getUUID());
    if (attachment == null) {
      return;
    }
    if (!HandsConfig.GRAPPLE_ENABLED.get()
        || !holdsPickaxe(player)
        || player.isSpectator()
        || !player.isAlive()
        || gap(player.getBoundingBox(), attachment.anchor)
            > HandsConfig.GRAPPLE_REACH.get() + SERVER_REACH_TOLERANCE) {
      detach(player);
      return;
    }
    Vec3 position = player.position();
    double moved = position.distanceTo(attachment.lastPosition);
    attachment.lastPosition = position;
    if (moved < TELEPORT_DISTANCE) {
      applyLoad(player, attachment, moved);
      attachment.soundDistance += moved;
      if (attachment.soundDistance >= 1.0) {
        attachment.soundDistance = 0.0;
        playSound(player.level(), attachment.anchor, player, false);
      }
    }
    // Server-side fall distance comes from the client's move packets; while the pickaxe carries the
    // player it must not add up into fall damage on landing.
    player.resetFallDistance();
  }

  private static void applyLoad(ServerPlayer player, Attachment attachment, double blocks) {
    attachment.carried += blocks;
    int damage = attachment.load.carry(blocks, HandsConfig.DURABILITY_PER_BLOCK.get());
    if (damage > 0) {
      player.getMainHandItem().hurtAndBreak(damage, player, EquipmentSlot.MAINHAND);
    }
  }

  private static void playSound(Level level, BlockPos anchor, Player player, boolean hit) {
    SoundType sound = level.getBlockState(anchor).getSoundType(level, anchor, player);
    level.playSound(
        null,
        anchor,
        hit ? sound.getHitSound() : sound.getStepSound(),
        SoundSource.PLAYERS,
        sound.getVolume() * (hit ? 0.6F : 0.3F),
        sound.getPitch());
  }

  @SubscribeEvent
  static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
    ATTACHED.remove(event.getEntity().getUUID());
  }

  /** {@code attached=false} from the server tells the client its grip was refused or lost. */
  public record GrapplePayload(boolean attached, BlockPos anchor) implements CustomPacketPayload {
    public static final Type<GrapplePayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath(GuestHands.MODID, "grapple"));
    public static final StreamCodec<ByteBuf, GrapplePayload> CODEC =
        StreamCodec.composite(
            ByteBufCodecs.BOOL,
            GrapplePayload::attached,
            BlockPos.STREAM_CODEC,
            GrapplePayload::anchor,
            GrapplePayload::new);

    @Override
    public Type<GrapplePayload> type() {
      return TYPE;
    }
  }
}
