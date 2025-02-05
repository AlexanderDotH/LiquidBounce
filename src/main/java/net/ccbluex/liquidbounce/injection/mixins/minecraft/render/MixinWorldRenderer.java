/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2024 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */

package net.ccbluex.liquidbounce.injection.mixins.minecraft.render;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.ccbluex.liquidbounce.common.OutlineFlag;
import net.ccbluex.liquidbounce.event.EventManager;
import net.ccbluex.liquidbounce.event.events.DrawOutlinesEvent;
import net.ccbluex.liquidbounce.features.module.modules.render.*;
import net.ccbluex.liquidbounce.render.engine.Color4b;
import net.ccbluex.liquidbounce.render.engine.RenderingFlags;
import net.ccbluex.liquidbounce.render.shader.shaders.OutlineShader;
import net.ccbluex.liquidbounce.utils.client.ClientUtilsKt;
import net.ccbluex.liquidbounce.utils.combat.CombatExtensionsKt;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.world.biome.Biome;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import static org.lwjgl.opengl.GL11.*;

@Mixin(WorldRenderer.class)
public abstract class MixinWorldRenderer {

    @Shadow
    @Nullable
    public Framebuffer entityOutlinesFramebuffer;

    @Shadow
    protected abstract void renderEntity(Entity entity, double cameraX, double cameraY, double cameraZ, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers);

    @Shadow
    public abstract @Nullable Framebuffer getEntityOutlinesFramebuffer();

    @Shadow
    protected abstract boolean canDrawEntityOutlines();

    @Shadow
    @Final
    private MinecraftClient client;

    @Inject(method = "loadEntityOutlinePostProcessor", at = @At("RETURN"))
    private void onLoadEntityOutlineShader(CallbackInfo info) {
        try {
            OutlineShader.INSTANCE.load();
        } catch (Throwable e) {
            ClientUtilsKt.getLogger().error("Failed to load outline shader", e);
        }
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void onRender(RenderTickCounter tickCounter, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f matrix4f, Matrix4f matrix4f2, CallbackInfo ci) {
        try {
            if (!OutlineShader.INSTANCE.isReady()) {
                return;
            }

            OutlineShader outlineShader = OutlineShader.INSTANCE;
            outlineShader.begin(2.0F);
            outlineShader.getFramebuffer().beginWrite(false);

            var event = new DrawOutlinesEvent(new MatrixStack(), camera, tickCounter.getTickDelta(false), DrawOutlinesEvent.OutlineType.INBUILT_OUTLINE);
            EventManager.INSTANCE.callEvent(event);

            if (event.getDirtyFlag()) {
                outlineShader.setDirty();
            }

            client.getFramebuffer().beginWrite(false);
        } catch (Throwable e) {
            ClientUtilsKt.getLogger().error("Failed to begin outline shader", e);
        }
    }

    @Inject(method = "renderEntity", at = @At("HEAD"))
    private void injectOutlineESP(Entity entity, double cameraX, double cameraY, double cameraZ, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, CallbackInfo info) {
        // Prevent stack overflow
        if (RenderingFlags.isCurrentlyRenderingEntityOutline().get() || !OutlineShader.INSTANCE.isReady()) {
            return;
        }

        Color4b color;

        if (ModuleESP.OutlineMode.INSTANCE.getRunning() && entity instanceof LivingEntity
                && CombatExtensionsKt.shouldBeShown(entity)) {
            color = ModuleESP.INSTANCE.getColor((LivingEntity) entity);
        } else if (ModuleItemESP.OutlineMode.INSTANCE.getRunning() && ModuleItemESP.INSTANCE.shouldRender(entity)) {
            color = ModuleItemESP.INSTANCE.getColor();
        } else {
            return;
        }

        OutlineShader outlineShader = OutlineShader.INSTANCE;
        Framebuffer originalBuffer = this.entityOutlinesFramebuffer;

        this.entityOutlinesFramebuffer = outlineShader.getFramebuffer();

        outlineShader.setColor(color);
        outlineShader.setDirty();

        RenderingFlags.isCurrentlyRenderingEntityOutline().set(true);

        try {
            renderEntity(entity, cameraX, cameraY, cameraZ, tickDelta, matrices,
                    outlineShader.getVertexConsumerProvider());
        } finally {
            RenderingFlags.isCurrentlyRenderingEntityOutline().set(false);
        }

        this.entityOutlinesFramebuffer = originalBuffer;
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/OutlineVertexConsumerProvider;draw()V"))
    private void onDrawOutlines(RenderTickCounter tickCounter, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f matrix4f, Matrix4f matrix4f2, CallbackInfo ci) {
        if (!ModuleESP.OutlineMode.INSTANCE.getRunning()) {
            return;
        }

        OutlineShader.INSTANCE.end(tickCounter.getTickDelta(false));
    }

    @Inject(method = "drawEntityOutlinesFramebuffer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gl/Framebuffer;draw(IIZ)V"))
    private void onDrawEntityOutlinesFramebuffer(CallbackInfo info) {
        if (OutlineShader.INSTANCE.isReady() && OutlineShader.INSTANCE.isDirty()) {
            OutlineShader.INSTANCE.drawFramebuffer();
        }
    }

    @Unique
    private boolean isRenderingChams = false;

    @Inject(method = "renderEntity", at = @At("HEAD"))
    private void injectChamsForEntity(Entity entity, double cameraX, double cameraY, double cameraZ, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, CallbackInfo ci) {
        if (ModuleChams.INSTANCE.getRunning() && CombatExtensionsKt.getCombatTargetsConfigurable().shouldShow(entity)) {
            glEnable(GL_POLYGON_OFFSET_FILL);
            glPolygonOffset(1f, -1000000F);

            this.isRenderingChams = true;
        }
    }

    @Inject(method = "renderEntity", at = @At("RETURN"))
    private void injectChamsForEntityPost(Entity entity, double cameraX, double cameraY, double cameraZ, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, CallbackInfo ci) {
        if (ModuleChams.INSTANCE.getRunning() && CombatExtensionsKt.getCombatTargetsConfigurable().shouldShow(entity) && this.isRenderingChams) {
            glPolygonOffset(1f, 1000000F);
            glDisable(GL_POLYGON_OFFSET_FILL);

            this.isRenderingChams = false;
        }
    }

    @Inject(method = "onResized", at = @At("HEAD"))
    private void onResized(int w, int h, CallbackInfo info) {
        OutlineShader.INSTANCE.onResized(w, h);
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;isSleeping()Z"))
    private boolean hookFreeCamRenderPlayerFromAllPerspectives(LivingEntity instance) {
        return ModuleFreeCam.INSTANCE.renderPlayerFromAllPerspectives(instance);
    }

    /**
     * Enables an outline glow when ESP is enabled and glow mode is active
     *
     * @author 1zuna
     */
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MinecraftClient;hasOutline(Lnet/minecraft/entity/Entity;)Z"))
    private boolean injectHasOutline(MinecraftClient instance, Entity entity) {
        if (ModuleItemESP.GlowMode.INSTANCE.getRunning() && ModuleItemESP.INSTANCE.shouldRender(entity)) {
            return true;
        }
        if (ModuleESP.GlowMode.INSTANCE.getRunning() && CombatExtensionsKt.shouldBeShown(entity)) {
            return true;
        }
        if (ModuleTNTTimer.INSTANCE.getRunning() && ModuleTNTTimer.INSTANCE.getEsp() && entity instanceof TntEntity) {
            return true;
        }

        if (ModuleStorageESP.Glow.INSTANCE.getRunning() && ModuleStorageESP.categorize(entity) != null) {
            return true;
        }

        return instance.hasOutline(entity);
    }

    /**
     * Inject ESP color as glow color
     *
     * @author 1zuna
     */
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getTeamColorValue()I"))
    private int injectTeamColor(Entity instance) {
        if (ModuleItemESP.GlowMode.INSTANCE.getRunning() && ModuleItemESP.INSTANCE.shouldRender(instance)) {
            return ModuleItemESP.INSTANCE.getColor().toARGB();
        }

        if (instance instanceof TntEntity && ModuleTNTTimer.INSTANCE.getRunning() && ModuleTNTTimer.INSTANCE.getEsp()) {
            return ModuleTNTTimer.INSTANCE.getTntColor(((TntEntity) instance).getFuse()).toARGB();
        }

        if (ModuleStorageESP.Glow.INSTANCE.getRunning()) {
            var categorizedEntity = ModuleStorageESP.categorize(instance);
            if (categorizedEntity != null) {
                return categorizedEntity.getColor().toARGB();
            }
        }

        if (instance instanceof LivingEntity && ModuleESP.GlowMode.INSTANCE.getRunning()) {
            final Color4b color = ModuleESP.INSTANCE.getColor((LivingEntity) instance);
            return color.toARGB();
        }

        return instance.getTeamColorValue();
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/OutlineVertexConsumerProvider;draw()V", shift = At.Shift.BEFORE))
    private void onRenderOutline(RenderTickCounter tickCounter, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f matrix4f, Matrix4f matrix4f2, CallbackInfo ci) {
        if (!this.canDrawEntityOutlines()) {
            return;
        }

        this.getEntityOutlinesFramebuffer().beginWrite(false);

        var event = new DrawOutlinesEvent(new MatrixStack(), camera, tickCounter.getTickDelta(false), DrawOutlinesEvent.OutlineType.MINECRAFT_GLOW);

        EventManager.INSTANCE.callEvent(event);

        OutlineFlag.drawOutline |= event.getDirtyFlag();

        MinecraftClient.getInstance().getFramebuffer().beginWrite(false);
    }

    @ModifyVariable(method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/BufferBuilderStorage;getOutlineVertexConsumers()Lnet/minecraft/client/render/OutlineVertexConsumerProvider;",
                    ordinal = 1),
            ordinal = 3,
            name = "bl3",
            require = 1
    )
    private boolean hookOutlineFlag(boolean bl3) {
        if (OutlineFlag.drawOutline) {
            OutlineFlag.drawOutline = false;
            return true;
        }

        return bl3;
    }

    @ModifyArg(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/WorldRenderer;setupTerrain(Lnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/Frustum;ZZ)V"), index = 3)
    private boolean renderSetupTerrainModifyArg(boolean spectator) {
        return ModuleFreeCam.INSTANCE.getRunning() || spectator;
    }

    @ModifyExpressionValue(method = "renderWeather", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/biome/Biome;getPrecipitation(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/world/biome/Biome$Precipitation;"))
    private Biome.Precipitation modifyBiomePrecipitation(Biome.Precipitation original) {
        var moduleOverrideWeather = ModuleCustomAmbience.INSTANCE;
        if (moduleOverrideWeather.getRunning() && moduleOverrideWeather.getWeather().get() == ModuleCustomAmbience.WeatherType.SNOWY) {
            return Biome.Precipitation.SNOW;
        }

        return original;
    }

    @ModifyExpressionValue(method = "renderWeather", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/world/ClientWorld;getRainGradient(F)F"))
    private float modifyPrecipitationGradient(float original) {
        var precipitation = ModuleCustomAmbience.Precipitation.INSTANCE;
        if (precipitation.getRunning() && original != 0f) {
            return precipitation.getGradient();
        }

        return original;
    }

    @ModifyVariable(method = "renderWeather", at = @At(value = "STORE"), ordinal = 3)
    private int modifyPrecipitationLayers(int original) {
        var precipitation = ModuleCustomAmbience.Precipitation.INSTANCE;
        if (precipitation.getRunning()) {
            return precipitation.getLayers();
        }

        return original;
    }

    @ModifyExpressionValue(method = "renderWeather", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MinecraftClient;isFancyGraphicsOrBetter()Z"))
    private boolean modifyPrecipitationLayersSet(boolean original) {
        var precipitation = ModuleCustomAmbience.Precipitation.INSTANCE;
        if (precipitation.getRunning()) {
            return false;
        }

        return original;
    }

    @ModifyExpressionValue(method = "tickRainSplashing", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/world/ClientWorld;getRainGradient(F)F"))
    private float removeRainSplashing(float original) {
        var moduleOverrideWeather = ModuleCustomAmbience.INSTANCE;
        if (moduleOverrideWeather.getRunning() && moduleOverrideWeather.getWeather().get() == ModuleCustomAmbience.WeatherType.SNOWY) {
            return 0f;
        }

        return original;
    }

    @ModifyArgs(
            method = "drawBlockOutline",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/WorldRenderer;drawCuboidShapeOutline(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;Lnet/minecraft/util/shape/VoxelShape;DDDFFFF)V"
            )
    )
    private void modifyBlockOutlineArgs(Args args) {
        // args: MatrixStack matrices,
        //		VertexConsumer vertexConsumer,
        //		VoxelShape shape,
        //		double offsetX,
        //		double offsetY,
        //		double offsetZ,
        //		float red,
        //		float green,
        //		float blue,
        //		float alpha

        if (!ModuleBlockOutline.INSTANCE.getRunning()) {
            return;
        }

        var color = ModuleBlockOutline.INSTANCE.getOutlineColor();
        var red = color.getR() / 255f;
        var green = color.getG() / 255f;
        var blue = color.getB() / 255f;
        var alpha = color.getA() / 255f;

        args.set(6, red);
        args.set(7, green);
        args.set(8, blue);
        args.set(9, alpha);
    }

}
