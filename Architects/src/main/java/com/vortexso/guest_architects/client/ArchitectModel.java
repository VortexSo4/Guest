package com.vortexso.guest_architects.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;

public class ArchitectModel extends HumanoidModel<HumanoidRenderState> {
  public static final int TEXTURE_WIDTH = 64;
  public static final int TEXTURE_HEIGHT = 64;

  public ArchitectModel(ModelPart root) {
    super(root);
  }

  public static LayerDefinition createBodyLayer() {
    MeshDefinition mesh = HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F);
    return LayerDefinition.create(mesh, TEXTURE_WIDTH, TEXTURE_HEIGHT);
  }

  @Override
  public void setupAnim(HumanoidRenderState state) {
    super.setupAnim(state);
  }
}
