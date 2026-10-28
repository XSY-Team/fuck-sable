package dev.fucksable.mixin;

import dev.fucksable.FuckSable;
import dev.fucksable.fix.FixRegistry;
import dev.ryanhcode.sable.companion.SableCompanion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
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
 * vista 5.5.0 起重构了该类：
 * <ul>
 *   <li>{@code setChunksForceLoaded} 被移除，force-load 统一走
 *       {@code setZoneChunksForced(MinecraftServer, GlobalPos, int, boolean)}；</li>
 *   <li>{@code updateVfReferences} 被移除，改为 {@code refreshWatchedViewFinders}。</li>
 * </ul>
 * 因此这里同时保留旧版注入（require = 0，新版静默跳过）并新增新版入口的坐标投影。
 * 注意 {@code acquireForceLoad}/{@code releaseForceLoad} 用原始 GlobalPos 做引用计数 key，
 * 只修改方法内参数不会破坏计数一致性。
 */
@Pseudo
@Mixin(targets = "net.mehvahdjukaar.vista.common.chunk_tracking.ServerCameraChunkManager", remap = false)
public class VistaCameraChunkFixMixin {

    /**
     * 旧版 vista（<= 5.4.x）：拦截 onServerPlayerTick 中对 setChunksForceLoaded 的调用（添加 ViewFinder 时）。
     */
    @Redirect(
            method = "onServerPlayerTick",
            at = @At(value = "INVOKE",
                    target = "Lnet/mehvahdjukaar/vista/common/chunk_tracking/ServerCameraChunkManager;setChunksForceLoaded(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;IZ)V"),
            remap = false,
            require = 0
    )
    private static void fucksable$projectAndForceLoadOnAdd(ServerLevel level, BlockPos pos, int radius, boolean force) {
        fucksable$setChunksForceLoadedProjected(level, pos, radius, force);
    }

    /**
     * 新版 vista（>= 5.5.0）：force-load 统一入口，在构造 Zone 中心 ChunkPos 时投影到主世界坐标。
     * <p>
     * 旧版 updateVfReferences 的注入已删除，因为该方法在 5.5.0 中不存在。
     * 只改方法内的 ChunkPos，不改 GlobalPos，避免影响 acquireForceLoad/releaseForceLoad 的引用计数 key。
     */
    @Redirect(
            method = "setZoneChunksForced",
            at = @At(value = "NEW",
                    target = "Lnet/minecraft/world/level/ChunkPos;<init>(Lnet/minecraft/core/BlockPos;)V"),
            remap = false,
            require = 0
    )
    private static ChunkPos fucksable$projectZoneCenter(BlockPos pos, MinecraftServer server, GlobalPos globalPos, int radius, boolean force) {
        if (!FixRegistry.isEnabled("vista-camera-chunk-fix")) return new ChunkPos(pos);

        ServerLevel level = server.getLevel(globalPos.dimension());
        if (level == null) return new ChunkPos(pos);

        try {
            Vec3 projected = SableCompanion.INSTANCE.projectOutOfSubLevel(level, Vec3.atLowerCornerOf(pos));
            BlockPos newPos = BlockPos.containing(projected);
            if (!newPos.equals(pos)) {
                FuckSable.LOGGER.debug("Vista camera chunk fix: projected zone center {} -> {}", pos, newPos);
                return new ChunkPos(newPos);
            }
        } catch (Exception e) {
            FuckSable.LOGGER.debug("Vista camera chunk fix: failed to project position", e);
        }

        return new ChunkPos(pos);
    }

    /**
     * 旧版 vista 的投影后 setChunksForceLoaded 实现。
     */
    private static void fucksable$setChunksForceLoadedProjected(ServerLevel level, BlockPos pos, int radius, boolean force) {
        if (FixRegistry.isEnabled("vista-camera-chunk-fix")) {
            try {
                Vec3 projected = SableCompanion.INSTANCE.projectOutOfSubLevel(level, Vec3.atLowerCornerOf(pos));
                BlockPos newPos = BlockPos.containing(projected);
                if (!newPos.equals(pos)) {
                    FuckSable.LOGGER.debug("Vista camera chunk fix: projected ViewFinder {} -> {}", pos, newPos);
                    pos = newPos;
                }
            } catch (Exception e) {
                FuckSable.LOGGER.debug("Vista camera chunk fix: failed to project position", e);
            }
        }

        ChunkPos cp = new ChunkPos(pos);
        ChunkPos.rangeClosed(cp, radius)
                .filter(p -> p.distanceSquared(cp) <= radius * radius)
                .forEach(p -> level.setChunkForced(p.x, p.z, force));
    }
}
