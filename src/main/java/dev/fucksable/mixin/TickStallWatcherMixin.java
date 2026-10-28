package dev.fucksable.mixin;

import dev.fucksable.debug.TickStallWatcher;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TEMPORARY DIAGNOSTIC: 在客户端 tick 前后通知 {@link TickStallWatcher}，
 * 让它在客户端 tick 卡住超过阈值时 dump 渲染线程（客户端主线程）的实时调用栈。
 * <p>
 * 属于客户端 mixin，只能在 "client" 列表中加载，服务端不存在 net.minecraft.client.Minecraft。
 */
@Mixin(Minecraft.class)
public class TickStallWatcherMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void fucksable$clientTickStart(CallbackInfo ci) {
        TickStallWatcher.onTickStart();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void fucksable$clientTickEnd(CallbackInfo ci) {
        TickStallWatcher.onTickEnd();
    }
}
