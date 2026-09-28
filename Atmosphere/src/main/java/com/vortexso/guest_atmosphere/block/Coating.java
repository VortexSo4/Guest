package com.vortexso.guest_atmosphere.block;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.ItemAbility;
import net.neoforged.neoforge.common.extensions.IBlockExtension;
import org.jspecify.annotations.Nullable;

public final class Coating {
  public enum Coat {
    SNOWY,
    SANDY;

    public String id() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public enum Family {
    MASONRY,
    WOOD
  }

  public enum Shape {
    FULL,
    STAIRS,
    SLAB,
    EDGE
  }

  public enum Base implements StringRepresentable {
    COBBLESTONE(
        Family.MASONRY,
        Blocks.COBBLESTONE,
        Blocks.COBBLESTONE_STAIRS,
        Blocks.COBBLESTONE_SLAB,
        Blocks.COBBLESTONE_WALL),
    MOSSY_COBBLESTONE(
        Family.MASONRY,
        Blocks.MOSSY_COBBLESTONE,
        Blocks.MOSSY_COBBLESTONE_STAIRS,
        Blocks.MOSSY_COBBLESTONE_SLAB,
        Blocks.MOSSY_COBBLESTONE_WALL),
    STONE(Family.MASONRY, Blocks.STONE, Blocks.STONE_STAIRS, Blocks.STONE_SLAB, null),
    STONE_BRICKS(
        Family.MASONRY,
        Blocks.STONE_BRICKS,
        Blocks.STONE_BRICK_STAIRS,
        Blocks.STONE_BRICK_SLAB,
        Blocks.STONE_BRICK_WALL),
    MOSSY_STONE_BRICKS(
        Family.MASONRY,
        Blocks.MOSSY_STONE_BRICKS,
        Blocks.MOSSY_STONE_BRICK_STAIRS,
        Blocks.MOSSY_STONE_BRICK_SLAB,
        Blocks.MOSSY_STONE_BRICK_WALL),
    CRACKED_STONE_BRICKS(Family.MASONRY, Blocks.CRACKED_STONE_BRICKS, null, null, null),
    CHISELED_STONE_BRICKS(Family.MASONRY, Blocks.CHISELED_STONE_BRICKS, null, null, null),
    BRICKS(
        Family.MASONRY, Blocks.BRICKS, Blocks.BRICK_STAIRS, Blocks.BRICK_SLAB, Blocks.BRICK_WALL),
    COBBLED_DEEPSLATE(
        Family.MASONRY,
        Blocks.COBBLED_DEEPSLATE,
        Blocks.COBBLED_DEEPSLATE_STAIRS,
        Blocks.COBBLED_DEEPSLATE_SLAB,
        Blocks.COBBLED_DEEPSLATE_WALL),
    DEEPSLATE_BRICKS(
        Family.MASONRY,
        Blocks.DEEPSLATE_BRICKS,
        Blocks.DEEPSLATE_BRICK_STAIRS,
        Blocks.DEEPSLATE_BRICK_SLAB,
        Blocks.DEEPSLATE_BRICK_WALL),
    MUD_BRICKS(
        Family.MASONRY,
        Blocks.MUD_BRICKS,
        Blocks.MUD_BRICK_STAIRS,
        Blocks.MUD_BRICK_SLAB,
        Blocks.MUD_BRICK_WALL),
    OAK(Family.WOOD, Blocks.OAK_PLANKS, Blocks.OAK_STAIRS, Blocks.OAK_SLAB, Blocks.OAK_FENCE),
    SPRUCE(
        Family.WOOD,
        Blocks.SPRUCE_PLANKS,
        Blocks.SPRUCE_STAIRS,
        Blocks.SPRUCE_SLAB,
        Blocks.SPRUCE_FENCE),
    BIRCH(
        Family.WOOD,
        Blocks.BIRCH_PLANKS,
        Blocks.BIRCH_STAIRS,
        Blocks.BIRCH_SLAB,
        Blocks.BIRCH_FENCE),
    JUNGLE(
        Family.WOOD,
        Blocks.JUNGLE_PLANKS,
        Blocks.JUNGLE_STAIRS,
        Blocks.JUNGLE_SLAB,
        Blocks.JUNGLE_FENCE),
    ACACIA(
        Family.WOOD,
        Blocks.ACACIA_PLANKS,
        Blocks.ACACIA_STAIRS,
        Blocks.ACACIA_SLAB,
        Blocks.ACACIA_FENCE),
    DARK_OAK(
        Family.WOOD,
        Blocks.DARK_OAK_PLANKS,
        Blocks.DARK_OAK_STAIRS,
        Blocks.DARK_OAK_SLAB,
        Blocks.DARK_OAK_FENCE),
    MANGROVE(
        Family.WOOD,
        Blocks.MANGROVE_PLANKS,
        Blocks.MANGROVE_STAIRS,
        Blocks.MANGROVE_SLAB,
        Blocks.MANGROVE_FENCE),
    CHERRY(
        Family.WOOD,
        Blocks.CHERRY_PLANKS,
        Blocks.CHERRY_STAIRS,
        Blocks.CHERRY_SLAB,
        Blocks.CHERRY_FENCE),
    PALE_OAK(
        Family.WOOD,
        Blocks.PALE_OAK_PLANKS,
        Blocks.PALE_OAK_STAIRS,
        Blocks.PALE_OAK_SLAB,
        Blocks.PALE_OAK_FENCE);

    private final Family family;
    private final @Nullable Block[] shapes;

    Base(Family family, @Nullable Block... shapes) {
      this.family = family;
      this.shapes = shapes;
    }

    public Family family() {
      return family;
    }

    public @Nullable Block block(Shape shape) {
      return shapes[shape.ordinal()];
    }

    @Override
    public String getSerializedName() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  private static @Nullable EnumProperty<Base> pending;

  private static Map<Block, Located> byVanilla;

  private Coating() {}

  static EnumProperty<Base> prepare(Family family, Shape shape) {
    pending =
        EnumProperty.create(
            "base", Base.class, base -> base.family == family && base.block(shape) != null);
    return pending;
  }

  public static @Nullable BlockState coat(BlockState vanilla, Coat coat) {
    Located located = vanillaMap().get(vanilla.getBlock());
    if (located == null) {
      return null;
    }
    Block block = AtmosphereBlocks.coated(coat, located.base.family, located.shape);
    Coated coated = (Coated) block;
    return block.withPropertiesOf(vanilla).setValue(coated.baseProperty(), located.base);
  }

  public static @Nullable BlockState uncoat(BlockState state) {
    if (!(state.getBlock() instanceof Coated coated)) {
      return null;
    }
    Block vanilla = state.getValue(coated.baseProperty()).block(coated.shape());
    return vanilla == null ? null : vanilla.withPropertiesOf(state);
  }

  public static @Nullable Coat coatOf(BlockState state) {
    return state.getBlock() instanceof Coated coated ? coated.coat() : null;
  }

  private static Map<Block, Located> vanillaMap() {
    if (byVanilla == null) {
      Map<Block, Located> map = new IdentityHashMap<>();
      for (Base base : Base.values()) {
        for (Shape shape : Shape.values()) {
          Block block = base.block(shape);
          if (block != null) {
            map.put(block, new Located(base, shape));
          }
        }
      }
      byVanilla = map;
    }
    return byVanilla;
  }

  private record Located(Base base, Shape shape) {}

  public interface Coated extends IBlockExtension {
    Coat coat();

    EnumProperty<Base> baseProperty();

    Shape shape();

    @Override
    default ItemStack getCloneItemStack(
        LevelReader level, BlockPos pos, BlockState state, boolean includeData, Player player) {
      BlockState vanilla = uncoat(state);
      return vanilla == null ? ItemStack.EMPTY : new ItemStack(vanilla.getBlock());
    }

    @Override
    default @Nullable BlockState getToolModifiedState(
        BlockState state, UseOnContext context, ItemAbility itemAbility, boolean simulate) {
      return itemAbility == ItemAbilities.SHOVEL_FLATTEN ? uncoat(state) : null;
    }
  }

  public static class Full extends Block implements Coated {
    private final Coat coat;
    private final EnumProperty<Base> base;

    Full(BlockBehaviour.Properties properties, Coat coat, EnumProperty<Base> base) {
      super(properties);
      this.coat = coat;
      this.base = base;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
      builder.add(pending);
    }

    @Override
    public Coat coat() {
      return coat;
    }

    @Override
    public EnumProperty<Base> baseProperty() {
      return base;
    }

    @Override
    public Shape shape() {
      return Shape.FULL;
    }
  }

  public static class Stairs extends StairBlock implements Coated {
    private final Coat coat;
    private final EnumProperty<Base> base;

    Stairs(BlockBehaviour.Properties properties, Coat coat, EnumProperty<Base> base) {
      super(Blocks.COBBLESTONE.defaultBlockState(), properties);
      this.coat = coat;
      this.base = base;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
      super.createBlockStateDefinition(builder);
      builder.add(pending);
    }

    @Override
    public Coat coat() {
      return coat;
    }

    @Override
    public EnumProperty<Base> baseProperty() {
      return base;
    }

    @Override
    public Shape shape() {
      return Shape.STAIRS;
    }
  }

  public static class Slab extends SlabBlock implements Coated {
    private final Coat coat;
    private final EnumProperty<Base> base;

    Slab(BlockBehaviour.Properties properties, Coat coat, EnumProperty<Base> base) {
      super(properties);
      this.coat = coat;
      this.base = base;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
      super.createBlockStateDefinition(builder);
      builder.add(pending);
    }

    @Override
    public Coat coat() {
      return coat;
    }

    @Override
    public EnumProperty<Base> baseProperty() {
      return base;
    }

    @Override
    public Shape shape() {
      return Shape.SLAB;
    }
  }

  public static class Wall extends WallBlock implements Coated {
    private final Coat coat;
    private final EnumProperty<Base> base;

    Wall(BlockBehaviour.Properties properties, Coat coat, EnumProperty<Base> base) {
      super(properties);
      this.coat = coat;
      this.base = base;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
      super.createBlockStateDefinition(builder);
      builder.add(pending);
    }

    @Override
    public Coat coat() {
      return coat;
    }

    @Override
    public EnumProperty<Base> baseProperty() {
      return base;
    }

    @Override
    public Shape shape() {
      return Shape.EDGE;
    }
  }

  public static class Fence extends FenceBlock implements Coated {
    private final Coat coat;
    private final EnumProperty<Base> base;

    Fence(BlockBehaviour.Properties properties, Coat coat, EnumProperty<Base> base) {
      super(properties);
      this.coat = coat;
      this.base = base;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
      super.createBlockStateDefinition(builder);
      builder.add(pending);
    }

    @Override
    public Coat coat() {
      return coat;
    }

    @Override
    public EnumProperty<Base> baseProperty() {
      return base;
    }

    @Override
    public Shape shape() {
      return Shape.EDGE;
    }
  }
}
