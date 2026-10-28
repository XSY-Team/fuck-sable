package dev.fucksable.mixin;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.render.ContraptionMatrices;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import dev.fucksable.fix.FixRegistry;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 修复 Simulated 1.3.2 高度传感器在动态结构上渲染时的客户端崩溃。
 * <p>
 * 崩溃：{@code NullPointerException: Cannot read field "y" because "context.position" is null}
 * <pre>
 * at AltitudeSensorMovementBehaviour.renderInContraption(AltitudeSensorMovementBehaviour.java:54)
 * at ContraptionEntityRenderer.renderActors
 * </pre>
 * 原因在 Simulated 自己的代码：同一个方法里先用
 * {@code context.position != null ? Sable.HELPER.projectOutOfSubLevel(...).y : 0} 算了一次高度，
 * 随后计算归一化高度时又直接读了 {@code context.position.y}，漏了判空。
 * 而 Create 在渲染阶段构造的 {@link MovementContext} 本来就不保证 {@code position} 非空
 * （渲染只需要 {@code localPos} + 变换矩阵），所以只要动态结构上有高度传感器就会崩。
 * <p>
 * 修复方式：渲染入口处若 {@code position} 为空，用动态结构实体的世界坐标 + 局部偏移补一个近似值，
 * 让后续的高度归一化计算有数据可用。只影响这一帧的渲染上下文，不会写回存档或服务端状态。
 */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.blocks.altitude_sensor.AltitudeSensorMovementBehaviour", remap = false)
public class AltitudeSensorRenderNpeFixMixin {

    @Inject(method = "renderInContraption", at = @At("HEAD"), remap = false)
    private void fucksable$fillMissingRenderPosition(MovementContext context, VirtualRenderWorld renderWorld,
                                                     ContraptionMatrices matrices, MultiBufferSource buffer,
                                                     CallbackInfo ci) {
        if (!FixRegistry.isEnabled("altitude-sensor-render-npe-fix")) return;
        if (context == null || context.position != null) return;

        final AbstractContraptionEntity entity = context.contraption != null ? context.contraption.entity : null;
        if (entity != null) {
            final Vec3 base = entity.position();
            context.position = context.localPos != null ? base.add(Vec3.atLowerCornerOf(context.localPos)) : base;
        } else {
            context.position = Vec3.ZERO;
        }
    }
}
