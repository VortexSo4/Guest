package com.vortexso.guest_atmosphere.block;

import com.vortexso.guest_atmosphere.GuestAtmosphere;
import com.vortexso.guest_atmosphere.block.Coating.Base;
import com.vortexso.guest_atmosphere.block.Coating.Coat;
import com.vortexso.guest_atmosphere.block.Coating.Family;
import com.vortexso.guest_atmosphere.block.Coating.Shape;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowyBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TallGrassBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.block.state.properties.WoodType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class AtmosphereBlocks {
  private static final DeferredRegister.Blocks BLOCKS =
      DeferredRegister.createBlocks(GuestAtmosphere.MODID);
  private static final DeferredRegister.Items ITEMS =
      DeferredRegister.createItems(GuestAtmosphere.MODID);

  private static final List<DeferredBlock<?>> NATURAL = new ArrayList<>();
  private static final List<DeferredBlock<?>> BUILDING = new ArrayList<>();
  private static final Map<Coat, Map<Family, List<DeferredBlock<Block>>>> COATED =
      new EnumMap<>(Coat.class);

  public static final DeferredBlock<TraceBlocks.LayerBlock> SAND_PILE =
      register("sand_pile", TraceBlocks.LayerBlock::new, p -> layer(p, MapColor.SAND), NATURAL);
  public static final DeferredBlock<TraceBlocks.LayerBlock> RED_SAND_PILE =
      register(
          "red_sand_pile",
          TraceBlocks.LayerBlock::new,
          p -> layer(p, MapColor.COLOR_ORANGE),
          NATURAL);
  public static final DeferredBlock<CoveredPlantBlock> SNOWY_PLANT =
      coveredPlant("snowy_plant", () -> Blocks.SNOW, MapColor.SNOW, SoundType.SNOW);
  public static final DeferredBlock<CoveredPlantBlock> SANDY_PLANT =
      coveredPlant("sandy_plant", SAND_PILE, MapColor.SAND, SoundType.SAND);
  public static final DeferredBlock<CoveredPlantBlock> RED_SANDY_PLANT =
      coveredPlant("red_sandy_plant", RED_SAND_PILE, MapColor.COLOR_ORANGE, SoundType.SAND);
  public static final DeferredBlock<TraceBlocks.LayerBlock> ASH =
      register("ash", TraceBlocks.LayerBlock::new, p -> layer(p, MapColor.COLOR_GRAY), NATURAL);

  public static final DeferredBlock<IcicleBlock> ICICLE =
      register(
          "icicle",
          IcicleBlock::new,
          p ->
              p.mapColor(MapColor.ICE)
                  .forceSolidOn()
                  .noOcclusion()
                  .sound(SoundType.GLASS)
                  .strength(0.3F)
                  .pushReaction(PushReaction.DESTROY)
                  .isRedstoneConductor((state, level, pos) -> false),
          NATURAL);
  public static final DeferredBlock<MultifaceBlock> FROST =
      register("frost", MultifaceBlock::new, p -> overlay(p, MapColor.SNOW), NATURAL);
  public static final DeferredBlock<TallGrassBlock> FROSTY_GRASS =
      register("frosty_grass", TallGrassBlock::new, AtmosphereBlocks::plant, NATURAL);
  public static final DeferredBlock<TallGrassBlock> FROSTY_FERN =
      register("frosty_fern", TallGrassBlock::new, AtmosphereBlocks::plant, NATURAL);
  public static final DeferredBlock<TransparentBlock> FROSTY_GLASS =
      register(
          "frosty_glass",
          TransparentBlock::new,
          p ->
              p.instrument(NoteBlockInstrument.HAT)
                  .strength(0.3F)
                  .sound(SoundType.GLASS)
                  .noOcclusion()
                  .isValidSpawn((state, level, pos, type) -> false)
                  .isRedstoneConductor((state, level, pos) -> false)
                  .isSuffocating((state, level, pos) -> false)
                  .isViewBlocking((state, level, pos) -> false),
          BUILDING);
  public static final DeferredBlock<IronBarsBlock> FROSTY_GLASS_PANE =
      register(
          "frosty_glass_pane",
          IronBarsBlock::new,
          p ->
              p.instrument(NoteBlockInstrument.HAT)
                  .strength(0.3F)
                  .sound(SoundType.GLASS)
                  .noOcclusion(),
          BUILDING);
  public static final DeferredBlock<TraceBlocks.ThinIceBlock> THIN_ICE =
      register(
          "thin_ice",
          TraceBlocks.ThinIceBlock::new,
          p ->
              p.mapColor(MapColor.ICE)
                  .friction(0.98F)
                  .randomTicks()
                  .strength(0.2F)
                  .sound(SoundType.GLASS)
                  .noOcclusion()
                  .isValidSpawn((state, level, pos, type) -> false)
                  .isRedstoneConductor((state, level, pos) -> false),
          NATURAL);
  public static final DeferredBlock<Block> PERMAFROST =
      register(
          "permafrost",
          Block::new,
          p -> p.mapColor(MapColor.ICE).strength(0.8F).sound(SoundType.GRAVEL),
          NATURAL);
  public static final DeferredBlock<SnowyBlock> CRYOSOL =
      register(
          "cryosol",
          SnowyBlock::new,
          p -> p.mapColor(MapColor.GRASS).strength(0.8F).sound(SoundType.GRASS),
          NATURAL);
  public static final DeferredBlock<Block> SILT =
      register(
          "silt",
          Block::new,
          p -> p.mapColor(MapColor.CLAY).strength(0.5F).sound(SoundType.MUD),
          NATURAL);
  public static final DeferredBlock<Block> CRACKED_MUD =
      register(
          "cracked_mud",
          Block::new,
          p -> p.mapColor(MapColor.TERRACOTTA_BROWN).strength(0.6F).sound(SoundType.PACKED_MUD),
          NATURAL);
  public static final DeferredBlock<MultifaceBlock> LICHEN =
      register("lichen", MultifaceBlock::new, p -> overlay(p, MapColor.GLOW_LICHEN), NATURAL);
  public static final DeferredBlock<VineBlock> IVY =
      register(
          "ivy",
          VineBlock::new,
          p ->
              p.mapColor(MapColor.PLANT)
                  .replaceable()
                  .noCollision()
                  .strength(0.2F)
                  .sound(SoundType.VINE)
                  .ignitedByLava()
                  .pushReaction(PushReaction.DESTROY),
          NATURAL);
  public static final DeferredBlock<MultifaceBlock> SOOT =
      register("soot", MultifaceBlock::new, p -> overlay(p, MapColor.COLOR_BLACK), NATURAL);

  public static final DeferredBlock<TraceBlocks.BurntLeavesBlock> BURNT_LEAVES =
      register(
          "burnt_leaves",
          TraceBlocks.BurntLeavesBlock::new,
          p ->
              p.mapColor(MapColor.COLOR_BLACK)
                  .strength(0.2F)
                  .sound(SoundType.GRASS)
                  .noOcclusion()
                  .isValidSpawn((state, level, pos, type) -> false)
                  .isSuffocating((state, level, pos) -> false)
                  .isViewBlocking((state, level, pos) -> false)
                  .isRedstoneConductor((state, level, pos) -> false)
                  .pushReaction(PushReaction.DESTROY),
          NATURAL);
  public static final DeferredBlock<TraceBlocks.CharredLogBlock> CHARRED_LOG =
      register(
          "charred_log", TraceBlocks.CharredLogBlock::new, AtmosphereBlocks::charred, BUILDING);
  public static final DeferredBlock<Block> CHARRED_PLANKS =
      register("charred_planks", Block::new, AtmosphereBlocks::charred, BUILDING);
  public static final DeferredBlock<StairBlock> CHARRED_STAIRS =
      register(
          "charred_stairs",
          p -> new StairBlock(Blocks.OAK_PLANKS.defaultBlockState(), p),
          AtmosphereBlocks::charred,
          BUILDING);
  public static final DeferredBlock<SlabBlock> CHARRED_SLAB =
      register("charred_slab", SlabBlock::new, AtmosphereBlocks::charred, BUILDING);
  public static final DeferredBlock<FenceBlock> CHARRED_FENCE =
      register("charred_fence", FenceBlock::new, p -> charred(p).forceSolidOn(), BUILDING);
  public static final DeferredBlock<FenceGateBlock> CHARRED_FENCE_GATE =
      register(
          "charred_fence_gate",
          p -> new FenceGateBlock(WoodType.OAK, p),
          p -> charred(p).forceSolidOn(),
          BUILDING);

  public static final DeferredBlock<TransparentBlock> VITRIFIED_SAND =
      register(
          "vitrified_sand",
          TransparentBlock::new,
          p ->
              p.mapColor(MapColor.SAND)
                  .strength(0.6F)
                  .sound(SoundType.GLASS)
                  .noOcclusion()
                  .isValidSpawn((state, level, pos, type) -> false)
                  .isRedstoneConductor((state, level, pos) -> false)
                  .isSuffocating((state, level, pos) -> false)
                  .isViewBlocking((state, level, pos) -> false),
          NATURAL);
  public static final DeferredBlock<AmethystClusterBlock> FULGURITE =
      register(
          "fulgurite",
          p -> new AmethystClusterBlock(7.0F, 10.0F, p),
          p ->
              p.mapColor(MapColor.SAND)
                  .forceSolidOn()
                  .noOcclusion()
                  .sound(SoundType.GLASS)
                  .strength(0.8F)
                  .pushReaction(PushReaction.DESTROY),
          NATURAL);
  public static final DeferredBlock<Block> CRACKED_BRICKS =
      register(
          "cracked_bricks",
          Block::new,
          p ->
              p.mapColor(MapColor.COLOR_RED)
                  .instrument(NoteBlockInstrument.BASEDRUM)
                  .requiresCorrectToolForDrops()
                  .strength(2.0F, 6.0F),
          BUILDING);

  public static final DeferredItem<FixationWandItem> FIXATION_WAND =
      ITEMS.registerItem("fixation_wand", FixationWandItem::new, p -> p.stacksTo(1));

  static {
    for (Coat coat : Coat.values()) {
      Map<Family, List<DeferredBlock<Block>>> families = new EnumMap<>(Family.class);
      families.put(Family.MASONRY, masonry(coat));
      families.put(Family.WOOD, wood(coat));
      COATED.put(coat, families);
    }
  }

  private AtmosphereBlocks() {}

  public static void register(IEventBus modBus) {
    BLOCKS.register(modBus);
    ITEMS.register(modBus);
    modBus.addListener(AtmosphereBlocks::onCreativeTabs);
  }

  static Block coated(Coat coat, Family family, Shape shape) {
    return COATED.get(coat).get(family).get(shape.ordinal()).get();
  }

  private static List<DeferredBlock<Block>> masonry(Coat coat) {
    String prefix = coat.id() + "_masonry";
    MapColor color = coat == Coat.SNOWY ? MapColor.SNOW : MapColor.SAND;
    UnaryOperator<BlockBehaviour.Properties> stone =
        p ->
            p.mapColor(color)
                .instrument(NoteBlockInstrument.BASEDRUM)
                .requiresCorrectToolForDrops()
                .strength(2.0F, 6.0F);
    return List.of(
        coatedBlock(
            prefix,
            p -> new Coating.Full(p, coat, Coating.prepare(Family.MASONRY, Shape.FULL)),
            stone),
        coatedBlock(
            prefix + "_stairs",
            p -> new Coating.Stairs(p, coat, Coating.prepare(Family.MASONRY, Shape.STAIRS)),
            stone),
        coatedBlock(
            prefix + "_slab",
            p -> new Coating.Slab(p, coat, Coating.prepare(Family.MASONRY, Shape.SLAB)),
            stone),
        coatedBlock(
            prefix + "_wall",
            p -> new Coating.Wall(p, coat, Coating.prepare(Family.MASONRY, Shape.EDGE)),
            p -> stone.apply(p).forceSolidOn()));
  }

  private static List<DeferredBlock<Block>> wood(Coat coat) {
    String prefix = coat.id();
    MapColor color = coat == Coat.SNOWY ? MapColor.SNOW : MapColor.SAND;
    UnaryOperator<BlockBehaviour.Properties> planks =
        p ->
            p.mapColor(color)
                .instrument(NoteBlockInstrument.BASS)
                .strength(2.0F, 3.0F)
                .sound(SoundType.WOOD)
                .ignitedByLava();
    return List.of(
        coatedBlock(
            prefix + "_planks",
            p -> new Coating.Full(p, coat, Coating.prepare(Family.WOOD, Shape.FULL)),
            planks),
        coatedBlock(
            prefix + "_wooden_stairs",
            p -> new Coating.Stairs(p, coat, Coating.prepare(Family.WOOD, Shape.STAIRS)),
            planks),
        coatedBlock(
            prefix + "_wooden_slab",
            p -> new Coating.Slab(p, coat, Coating.prepare(Family.WOOD, Shape.SLAB)),
            planks),
        coatedBlock(
            prefix + "_wooden_fence",
            p -> new Coating.Fence(p, coat, Coating.prepare(Family.WOOD, Shape.EDGE)),
            p -> planks.apply(p).forceSolidOn()));
  }

  @SuppressWarnings("unchecked")
  private static DeferredBlock<Block> coatedBlock(
      String name,
      Function<BlockBehaviour.Properties, ? extends Block> factory,
      UnaryOperator<BlockBehaviour.Properties> properties) {
    return (DeferredBlock<Block>) (DeferredBlock<?>) register(name, factory, properties, null);
  }

  private static <B extends Block> DeferredBlock<B> register(
      String name,
      Function<BlockBehaviour.Properties, ? extends B> factory,
      UnaryOperator<BlockBehaviour.Properties> properties,
      List<DeferredBlock<?>> tab) {
    DeferredBlock<B> block = BLOCKS.registerBlock(name, factory, properties);
    ITEMS.registerSimpleBlockItem(block);
    if (tab != null) {
      tab.add(block);
    }
    return block;
  }

  private static DeferredBlock<CoveredPlantBlock> coveredPlant(
      String name, Supplier<? extends Block> cover, MapColor color, SoundType sound) {
    return register(
        name,
        p -> new CoveredPlantBlock(p, cover),
        p ->
            p.mapColor(color)
                .forceSolidOff()
                .strength(0.1F)
                .sound(sound)
                .isViewBlocking(
                    (state, level, pos) -> state.getValue(CoveredPlantBlock.LAYERS) >= 8)
                .pushReaction(PushReaction.DESTROY),
        null);
  }

  public static List<CoveredPlantBlock> coveredPlants() {
    return List.of(SNOWY_PLANT.get(), SANDY_PLANT.get(), RED_SANDY_PLANT.get());
  }

  private static BlockBehaviour.Properties layer(BlockBehaviour.Properties p, MapColor color) {
    return p.mapColor(color)
        .replaceable()
        .forceSolidOff()
        .strength(0.1F)
        .sound(SoundType.SAND)
        .isViewBlocking((state, level, pos) -> state.getValue(TraceBlocks.LayerBlock.LAYERS) >= 8)
        .pushReaction(PushReaction.DESTROY);
  }

  private static BlockBehaviour.Properties overlay(BlockBehaviour.Properties p, MapColor color) {
    return p.mapColor(color)
        .replaceable()
        .noCollision()
        .strength(0.1F)
        .sound(SoundType.POWDER_SNOW)
        .pushReaction(PushReaction.DESTROY);
  }

  private static BlockBehaviour.Properties plant(BlockBehaviour.Properties p) {
    return p.mapColor(MapColor.PLANT)
        .replaceable()
        .noCollision()
        .instabreak()
        .sound(SoundType.GRASS)
        .offsetType(BlockBehaviour.OffsetType.XYZ)
        .ignitedByLava()
        .pushReaction(PushReaction.DESTROY);
  }

  private static BlockBehaviour.Properties charred(BlockBehaviour.Properties p) {
    return p.mapColor(MapColor.COLOR_BLACK)
        .instrument(NoteBlockInstrument.BASS)
        .strength(1.5F, 2.0F)
        .sound(SoundType.WOOD);
  }

  private static void onCreativeTabs(BuildCreativeModeTabContentsEvent event) {
    if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
      event.accept(FIXATION_WAND);
    } else if (event.getTabKey() == CreativeModeTabs.NATURAL_BLOCKS) {
      NATURAL.forEach(block -> event.accept(block));
    } else if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
      BUILDING.forEach(block -> event.accept(block));

      for (Coat coat : Coat.values()) {
        for (Family family : Family.values()) {
          List<DeferredBlock<Block>> shapes = COATED.get(coat).get(family);
          for (Base base : Base.values()) {
            if (base.family() != family) {
              continue;
            }
            for (Shape shape : Shape.values()) {
              Block vanilla = base.block(shape);
              if (vanilla == null) {
                continue;
              }
              ItemStack stack = new ItemStack(shapes.get(shape.ordinal()).get());
              stack.set(
                  DataComponents.BLOCK_STATE,
                  BlockItemStateProperties.EMPTY.with(
                      ((Coating.Coated) shapes.get(shape.ordinal()).get()).baseProperty(), base));
              stack.set(
                  DataComponents.ITEM_NAME,
                  Component.translatable(
                      "block.guest_atmosphere.coated." + coat.id(), vanilla.getName()));
              event.accept(stack);
            }
          }
        }
      }
    }
  }
}
