package dev.fucksable.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.fucksable.fix.HeightOverrideState;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 实验性：覆盖原版世界高度上限。
 * <p>
 * LevelHeightAccessor 的方法分布：
 * - getMaxBuildHeight() 是 default 方法，Level 类没有 override，所以必须拦截接口的 default 方法。
 * - getMinBuildHeight() 是抽象方法，Level 类没有 override，由子类实现。
 * <p>
 * 通过 instanceof Level 检查确保只影响 Level 及其子类（ServerLevel/ClientLevel），
 * 不影响 LevelChunk 等其他实现类。
 * <p>
 * 性能注意（v1.8.0 第二次修正）：这个方法在方块查询/射线裁剪路径上是极热的
 * （{@code traverseBlocks} 每格调用两次 {@code isOutsideBuildHeight}）。
 * <ul>
 *   <li>不能用 {@code @Inject} + {@code CallbackInfoReturnable}：Mixin 每次调用都会
 *       new 一个 CallbackInfoReturnable，几十万次/秒的调用直接变成 GC 压力，
 *       表现为客户端 tick 偶发卡 1 秒以上。</li>
 *   <li>改用 MixinExtras 的 {@link ModifyReturnValue}，只在 return 处插入一次静态调用，
 *       不分配对象。</li>
 *   <li>开关和高度值来自 {@link HeightOverrideState} 的 volatile 缓存，
 *       不在热路径上做 map 查找。</li>
 * </ul>
 * 另外该 mixin 只在配置文件里显式启用时才会被应用（见 FuckSableMixinConfigPlugin）。
 */
@Mixin(LevelHeightAccessor.class)
public interface WorldHeightOverrideMixin extends LevelHeightAccessor {

    @ModifyReturnValue(method = "getMaxBuildHeight()I", at = @At("RETURN"))
    default int fucksable$overrideMaxBuildHeight(int original) {
        if (!HeightOverrideState.isEnabled()) return original;
        if (!((Object) this instanceof Level)) return original;
        return HeightOverrideState.maxBuildHeight();
    }

    @Override
    default int getMinBuildHeight() {
        if (HeightOverrideState.isEnabled() && (Object) this instanceof Level) {
            return HeightOverrideState.minBuildHeight();
        }
        return -64;
    }
}
