package dev.fucksable.mixin;

import dev.fucksable.ThrottledLogger;
import dev.fucksable.fix.FixRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 丢弃跨坐标系的超长射线，避免单次 {@code clip} 遍历数百万格导致 tick 卡死。
 * <p>
 * 问题分析：
 * Sable 的 {@code BlockGetterMixin.clip}（一个 {@code @Overwrite} 的 interface default 方法）
 * 对射线的起点和终点**各自独立**判断是否位于 SubLevel 内，只投影命中的那一端：
 * <pre>
 * final SubLevel fromSubLevel = helper.getContaining(level, clipContext.getFrom());
 * if (fromSubLevel != null) { ...把 from 投影到世界坐标... }
 * final SubLevel toSubLevel = helper.getContaining(level, clipContext.getTo());
 * if (toSubLevel != null) { ...把 to 投影到世界坐标... }
 * minResult = originalClip(self, clipContext);
 * </pre>
 * 物理结构上的方块实体（例如 Aeronautics 的螺旋桨）拿到的 {@code getPos()} 是结构内部坐标。
 * 当粒子/命中射线的起点在结构包围盒内、终点因为超出结构边界而落到外面时，
 * 只有起点被投影，终点仍是结构布局坐标 —— 射线变成"一端世界坐标、一端布局坐标"，
 * 长度可达几十万到几百万格。{@code BlockGetter.traverseBlocks} 会逐格遍历，
 * 实测一次调用就能把客户端 tick 卡住 6 秒以上。
 * <p>
 * 修复方式：在 {@code clip} 入口检查射线段长度，超过 1024 格直接返回 miss。
 * 这种射线本身没有任何合法用途，返回 miss 只会让对应的粒子/命中效果不生成，
 * 不会影响玩家瞄准、破坏方块等正常射线（它们的长度在数十格量级）。
 * <p>
 * 优先级说明：必须低于 Sable 的 1100（本 mixin 用 1000），否则注入会被 Sable 的
 * {@code @Overwrite} 整体覆盖。全局配置里的 1500 会被 {@code @Mixin} 注解上的 priority 覆盖。
 */
@Mixin(value = BlockGetter.class, priority = 1000)
public interface BlockGetterRayLengthGuardMixin {

    @Inject(method = "clip", at = @At("HEAD"), cancellable = true)
    default void fucksable$guardAgainstRunawayRay(ClipContext context, CallbackInfoReturnable<BlockHitResult> cir) {
        if (!FixRegistry.isEnabled("clip-ray-length-guard")) return;

        // 允许的最长射线距离（格），超过即视为跨坐标系射线
        final double maxRayLength = 1024.0;

        final Vec3 from = context.getFrom();
        final Vec3 to = context.getTo();
        final double distanceSqr = from.distanceToSqr(to);
        if (distanceSqr <= maxRayLength * maxRayLength) return;

        ThrottledLogger.warn("clip-ray-guard",
            "Dropped an over-long raycast ({} -> {}, length {} blocks). This usually means Sable projected only one endpoint out of a sub-level.",
            from, to, String.format("%.0f", Math.sqrt(distanceSqr)));

        final Vec3 diff = from.subtract(to);
        cir.setReturnValue(BlockHitResult.miss(to, Direction.getNearest(diff.x, diff.y, diff.z), BlockPos.containing(to)));
    }
}
