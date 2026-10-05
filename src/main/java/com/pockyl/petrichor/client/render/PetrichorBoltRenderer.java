package com.pockyl.petrichor.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LightningBoltRenderer;
import net.minecraft.world.entity.LightningBolt;

import com.pockyl.petrichor.ClientConfig;

/** Lightning bolt entities are drawn by the mod's lightning system; this renderer only stands in for vanilla's. */
public final class PetrichorBoltRenderer extends LightningBoltRenderer {
    public PetrichorBoltRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(LightningBolt entity, float entityYaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffer,
            int packedLight) {
        if (!ClientConfig.BOLTS.get()) {
            super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
        }
    }
}
