package dev.fucksable.mixin;

import dev.fucksable.FuckSable;
import dev.fucksable.fix.FixRegistry;
import dev.ryanhcode.sable.companion.SableCompanion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 修复 Vista 摄像头区块加载与 Sable 物理结构的不兼容问题。
 * <p>
 * 当 ViewFinder 放置在物理结构（SubLevel）上时，其 GlobalPos 中的坐标是 SubLevel 内部坐标，
 * 但 ServerCameraChunkManager 会在主世界上 force-load 这些坐标对应的区块，
 * 导致 force-load 了错误位置的区块，可能触发无限加载循环、TPS 掉 0 等问题。
 * <p>
 * 修复方式：拦截 setZoneChunksForced 中的 GlobalPos.pos()，将 ViewFinder 位置通过
 * SableCompanion.projectOutOfSubLevel 投影到主世界坐标后再 force-load。
 */
@Pseudo
@Mixin(targets = "net.mehvahdjukaar.vista.common.chunk_tracking.ServerCameraChunkManager", remap = false)
public class VistaCameraChunkFixMixin {

    /**
     * 添加和移除 ViewFinder 都经过 setZoneChunksForced，
     * 将其中的 vf.pos() 投影到主世界坐标后再 force-load / unforce-load。
     */
    @Redirect(
            method = "setZoneChunksForced",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/core/GlobalPos;pos()Lnet/minecraft/core/BlockPos;"),
            remap = false
    )
    private static BlockPos fucksable$projectZonePos(GlobalPos vf, MinecraftServer server, GlobalPos globalPos, int radius, boolean force) {
        BlockPos pos = vf.pos();
        ServerLevel level = server.getLevel(vf.dimension());
        if (level == null || !FixRegistry.isEnabled("vista-camera-chunk-fix")) {
            return pos;
        }
        try {
            BlockPos newPos = BlockPos.containing(SableCompanion.INSTANCE.projectOutOfSubLevel(level, Vec3.atLowerCornerOf(pos)));
            if (!newPos.equals(pos)) {
                FuckSable.LOGGER.debug("Vista camera chunk fix: projected ViewFinder {} -> {}", pos, newPos);
            }
            return newPos;
        } catch (Exception e) {
            FuckSable.LOGGER.debug("Vista camera chunk fix: failed to project position", e);
            return pos;
        }
    }
}
