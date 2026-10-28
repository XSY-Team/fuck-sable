package dev.fucksable.mixin;

import dev.fucksable.FuckSable;
import dev.fucksable.fix.CorruptedPointerCache;
import dev.fucksable.fix.FixRegistry;
import dev.fucksable.storage.SubLevelStorageIO;
import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.holding.SavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelStorage;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Set;

/**
 * async-save：把 Sable 的 SubLevel 磁盘 I/O 从主线程搬到单线程串行队列。
 * <p>
 * 设计要点（v1.8.0 重构）：
 * <ul>
 *   <li>序列化（含 PalettedContainer.pack）仍在主线程完成，只有磁盘写入异步。
 *       PalettedContainer 带 ThreadingDetector，不能跨线程。</li>
 *   <li>所有磁盘任务提交到同一个 {@link SubLevelStorageIO} 实例，严格 FIFO 串行。
 *       Sable 的 regionCache/storageCache、SubLevelStorageFile 的共享 header 缓冲区
 *       都不是线程安全的，两线程同时碰同一个 storage 就会损坏存档结构。</li>
 *   <li>flush 与 pruneCache 都进同一条任务链，并且顺序固定（flush 先、pruneCache 后）。
 *       v1.7.25 及之前 pruneCache 留在主线程执行，与异步 flush 并发：
 *       一边 force、一边 delete/close，导致主线程卡在 pruneCache（Issue #23/#28），
 *       flush 迭代器读到被移除的 null 条目（Issue #29）。</li>
 *   <li>读取（getOrLoadHoldingChunk）与下一次 saveAll、close 之前先 drain，
 *       保证读到最新数据，也保证没有任务在 storage 关闭后仍在跑。</li>
 * </ul>
 */
@Mixin(SubLevelHoldingChunkMap.class)
public abstract class SubLevelHoldingChunkMapMixin {

    @Unique
    private SubLevelStorageIO fucksable$io;

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void fucksable$init(CallbackInfo ci) {
        this.fucksable$io = new SubLevelStorageIO("level");
    }

    /**
     * 等待已提交的磁盘任务全部完成。
     */
    @Unique
    private void fucksable$drain() {
        if (this.fucksable$io != null) {
            this.fucksable$io.drain();
        }
    }

    /**
     * async-save 是否生效。修复项关闭时退化为同步执行，但仍先排空队列，
     * 避免运行中关闭开关后同步路径与遗留异步任务并发访问同一 storage。
     */
    @Unique
    private boolean fucksable$useAsync() {
        return FixRegistry.isEnabled("async-save") && this.fucksable$io != null;
    }

    // 下一次 saveAll 开始前，先等上一批 I/O 完成，避免两次保存的写入交错
    @Inject(method = "saveAll", at = @At("HEAD"), remap = false)
    private void fucksable$drainBeforeSaveAll(CallbackInfo ci) {
        this.fucksable$drain();
    }

    @Redirect(
        method = "saveAll",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/sublevel/storage/serialization/SubLevelStorage;attemptSaveSubLevel(Ldev/ryanhcode/sable/sublevel/storage/holding/GlobalSavedSubLevelPointer;Ldev/ryanhcode/sable/sublevel/storage/serialization/SubLevelData;)V", remap = false),
        remap = false
    )
    private void fucksable$wrapSaveSubLevel(SubLevelStorage storage, GlobalSavedSubLevelPointer pointer, SubLevelData data) {
        if (!this.fucksable$useAsync()) {
            this.fucksable$drain();
            try {
                storage.attemptSaveSubLevel(pointer, data);
            } catch (Exception e) {
                FuckSable.LOGGER.error("Failed to save sub-level for pointer {}, skipping", pointer, e);
            }
            return;
        }

        this.fucksable$io.submit(() -> {
            try {
                storage.attemptSaveSubLevel(pointer, data);
            } catch (Exception e) {
                FuckSable.LOGGER.error("Failed to save sub-level for pointer {}, skipping", pointer, e);
            }
        });
    }

    @Redirect(
        method = "saveAll",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/sublevel/storage/serialization/SubLevelStorage;attemptSaveHoldingChunk(Lnet/minecraft/world/level/ChunkPos;Ldev/ryanhcode/sable/sublevel/storage/holding/SubLevelHoldingChunk;)V", remap = false),
        remap = false
    )
    private void fucksable$wrapSaveHoldingChunk(SubLevelStorage storage, ChunkPos chunkPos, SubLevelHoldingChunk holdingChunk) {
        if (!this.fucksable$useAsync()) {
            this.fucksable$drain();
            try {
                storage.attemptSaveHoldingChunk(chunkPos, holdingChunk);
            } catch (Exception e) {
                FuckSable.LOGGER.error("Failed to save holding chunk at {}, skipping", chunkPos, e);
            }
            return;
        }

        this.fucksable$io.submit(() -> {
            try {
                storage.attemptSaveHoldingChunk(chunkPos, holdingChunk);
            } catch (Exception e) {
                FuckSable.LOGGER.error("Failed to save holding chunk at {}, skipping", chunkPos, e);
            }
        });
    }

    /**
     * 空 holding chunk 的移除同样走队列（Sable 2.0.4+ 新增的 attemptRemoveHoldingChunk）。
     */
    @Redirect(
        method = "saveAll",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/sublevel/storage/serialization/SubLevelStorage;attemptRemoveHoldingChunk(Lnet/minecraft/world/level/ChunkPos;)V", remap = false),
        remap = false,
        require = 0
    )
    private void fucksable$wrapRemoveHoldingChunk(SubLevelStorage storage, ChunkPos chunkPos) {
        if (!this.fucksable$useAsync()) {
            this.fucksable$drain();
            try {
                storage.attemptRemoveHoldingChunk(chunkPos);
            } catch (Exception e) {
                FuckSable.LOGGER.error("Failed to remove holding chunk at {}, skipping", chunkPos, e);
            }
            return;
        }

        this.fucksable$io.submit(() -> {
            try {
                storage.attemptRemoveHoldingChunk(chunkPos);
            } catch (Exception e) {
                FuckSable.LOGGER.error("Failed to remove holding chunk at {}, skipping", chunkPos, e);
            }
        });
    }

    /**
     * flush 入队执行，主线程不再等待磁盘 force。
     */
    @Redirect(
        method = "saveAll",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/sublevel/storage/serialization/SubLevelStorage;flush()V", remap = false),
        remap = false
    )
    private void fucksable$deferFlush(SubLevelStorage storage) {
        if (!this.fucksable$useAsync()) {
            this.fucksable$drain();
            try {
                storage.flush();
            } catch (Exception e) {
                FuckSable.LOGGER.error("Failed to flush sub-level storage", e);
            }
            return;
        }

        this.fucksable$io.submit(() -> {
            try {
                storage.flush();
            } catch (Exception e) {
                FuckSable.LOGGER.error("Failed to flush sub-level storage", e);
            }
        });
    }

    /**
     * pruneCache 入队执行，排在 flush 之后。
     * <p>
     * 这是 Issue #23/#28 的核心：pruneCache 会 delete/close 空存储文件，
     * 与异步 flush 的 force 并发时会把主线程卡住几十秒甚至触发 watchdog。
     * 放进同一条串行链后，两者不再有任何并发窗口。
     */
    @Redirect(
        method = "saveAll",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/sublevel/storage/serialization/SubLevelStorage;pruneCache()V", remap = false),
        remap = false,
        require = 0
    )
    private void fucksable$deferPruneCache(SubLevelStorage storage) {
        if (!this.fucksable$useAsync()) {
            this.fucksable$drain();
            try {
                storage.pruneCache();
            } catch (Exception e) {
                FuckSable.LOGGER.error("Failed to prune sub-level storage cache", e);
            }
            return;
        }

        this.fucksable$io.submit(() -> {
            try {
                storage.pruneCache();
            } catch (Exception e) {
                FuckSable.LOGGER.error("Failed to prune sub-level storage cache", e);
            }
        });
    }

    // 读取屏障：任何磁盘读取前先排空队列，保证读到的数据包含已提交的写入
    @Inject(method = "getOrLoadHoldingChunk", at = @At("HEAD"), remap = false)
    private void fucksable$drainBeforeStorageRead(ChunkPos chunkPos, boolean create, CallbackInfoReturnable<SubLevelHoldingChunk> cir) {
        this.fucksable$drain();
    }

    // --- corrupted-cleanup 修复项 ---

    /**
     * 在 getOrLoadHoldingChunk 返回前，移除所有已知损坏的指针。
     * 损坏检测和缓存已在 SubLevelStorageMixin 中完成。
     */
    @Inject(method = "getOrLoadHoldingChunk", at = @At("RETURN"), remap = false)
    private void fucksable$cleanupCorruptedPointers(ChunkPos chunkPos, boolean create, CallbackInfoReturnable<SubLevelHoldingChunk> cir) {
        if (!FixRegistry.isEnabled("corrupted-cleanup")) return;

        SubLevelHoldingChunk loadedChunk = cir.getReturnValue();
        if (loadedChunk == null) return;

        Set<SavedSubLevelPointer> knownCorrupted = CorruptedPointerCache.getCorrupted(chunkPos.toLong());
        if (knownCorrupted != null && !knownCorrupted.isEmpty()) {
            List<SavedSubLevelPointer> pointers = loadedChunk.getSubLevelPointers();
            int before = pointers.size();
            pointers.removeAll(knownCorrupted);
            int removed = before - pointers.size();
            if (removed > 0) {
                FuckSable.LOGGER.info("Removed {} corrupted pointer(s) from holding chunk at {}", removed, chunkPos);
            }
        }
    }

    @Inject(method = "close", at = @At("HEAD"), remap = false)
    private void fucksable$closeIO(CallbackInfo ci) {
        // 关闭 storage 文件之前，必须确保队列里所有写入、flush、prune 都已完成
        if (this.fucksable$io != null) {
            this.fucksable$io.close();
        }
    }
}
