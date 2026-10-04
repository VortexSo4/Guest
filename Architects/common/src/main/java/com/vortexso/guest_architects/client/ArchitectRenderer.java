package com.vortexso.guest_architects.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vortexso.guest_architects.GuestArchitects;
import com.vortexso.guest_architects.entity.Architect;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.EyesLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

public class ArchitectRenderer
    extends HumanoidMobRenderer<Architect, HumanoidRenderState, ArchitectModel> {
  private static final Identifier TEXTURE =
      Identifier.fromNamespaceAndPath(GuestArchitects.MODID, "textures/entity/architect.png");
  private static final RenderType EYES =
      RenderTypes.eyes(
          Identifier.fromNamespaceAndPath(
              GuestArchitects.MODID, "textures/entity/architect_eyes.png"));

  private static final float WIDTH_SCALE = 0.82F;
  private static final float HEIGHT_SCALE = 1.17F;
  private static final float YOUNG_SCALE = 0.6F;

  public ArchitectRenderer(EntityRendererProvider.Context context) {
    super(context, new ArchitectModel(context.bakeLayer(ArchitectsClient.ARCHITECT_LAYER)), 0.4F);
    addLayer(
        new EyesLayer<>(this) {
          @Override
          public RenderType renderType() {
            return EYES;
          }
        });
  }

  @Override
  public Identifier getTextureLocation(HumanoidRenderState state) {
    return TEXTURE;
  }

  @Override
  public HumanoidRenderState createRenderState() {
    return new HumanoidRenderState();
  }

  @Override
  protected void scale(HumanoidRenderState state, PoseStack poseStack) {
    float age = state.isBaby ? YOUNG_SCALE : 1.0F;
    poseStack.scale(WIDTH_SCALE * age, HEIGHT_SCALE * age, WIDTH_SCALE * age);
  }
}
