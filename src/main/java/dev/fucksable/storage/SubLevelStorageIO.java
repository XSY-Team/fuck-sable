package dev.fucksable.storage;

import dev.fucksable.FuckSable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个 SubLevelStorage 的串行 I/O 队列。
 * <p>
 * Sable 的 {@code SubLevelStorage}、{@code SubLevelStorageFile}、{@code SubLevelRegionFile}
 * 内部的缓存（Long2ObjectLinkedOpenHashMap、BitSet、共享 header ByteBuffer）和文件句柄
 * 全都不是线程安全的。只要两个线程同时碰同一个 storage 实例，就可能出现：
 * <ul>
 *   <li>FastUtil 链表 map 结构损坏（Index -1 out of bounds）</li>
 *   <li>flush 遍历时读到 null 条目（NPE: regionFile is null）</li>
 *   <li>pruneCache 删除/关闭文件与 flush 的 force 并发，主线程长时间阻塞</li>
 *   <li>header/sectorSpans 共享缓冲区被并发改写，存档指针损坏</li>
 * </ul>
 * <p>
 * 因此这里把所有磁盘任务放进唯一一条 FIFO 任务链，由单个 daemon 线程依次执行。
 * 主线程只在必要时（读取、关闭）等待链尾，其余写操作不阻塞。
 * <p>
 * 关键约束：{@code flush} 与 {@code pruneCache} 必须严格按提交顺序串行，
 * 不能各自提交到线程池（线程池不保证调度顺序），否则 pruneCache 可能先于 flush
 * 执行，重新引入并发删除与 force 的竞争。
 */
public final class SubLevelStorageIO {

    private static final AtomicLong THREAD_COUNTER = new AtomicLong();
    private static final ThreadLocal<SubLevelStorageIO> CURRENT_QUEUE = new ThreadLocal<>();

    private final String name;
    private final ExecutorService executor;

    /** 任务链尾。所有任务挂在同一条链上，保证全局 FIFO。 */
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);

    private boolean closed;

    public SubLevelStorageIO(final String name) {
        this.name = name + "-" + THREAD_COUNTER.incrementAndGet();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            final Thread thread = new Thread(r, "fuckSable Sub-Level I/O " + this.name);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 当前线程是否就是本队列的执行线程。
     * <p>
     * 队列任务内部如果再触发 drain（例如 I/O 线程上调用 close），必须跳过等待，否则自锁。
     */
    public boolean isQueueThread() {
        return CURRENT_QUEUE.get() == this;
    }

    /**
     * 提交一个异步磁盘任务，不等待执行完成。
     * <p>
     * 任务总是追加到链尾，因此提交顺序即为执行顺序。
     */
    public synchronized void submit(final Runnable task) {
        if (this.closed) {
            // 队列已关闭：退化为同步执行，避免数据丢失
            runSafely(task);
            return;
        }

        this.tail = this.tail
            .thenRunAsync(() -> runSafely(task), this.executor)
            .exceptionally(throwable -> null);
    }

    /**
     * 等待当前已提交的全部任务完成。
     * <p>
     * 只允许在主线程使用；在队列线程上调用会直接返回。
     */
    public void drain() {
        if (isQueueThread()) {
            return;
        }

        while (true) {
            final CompletableFuture<Void> snapshot;
            synchronized (this) {
                snapshot = this.tail;
            }

            try {
                snapshot.join();
            } catch (final CompletionException e) {
                FuckSable.LOGGER.error("Failed to drain async sub-level disk IO ({})", this.name, e);
            }

            synchronized (this) {
                if (snapshot == this.tail) {
                    return;
                }
            }
        }
    }

    /**
     * 排空任务链并关闭执行线程。关闭后提交的任务会退化为同步执行。
     */
    public void close() {
        drain();

        synchronized (this) {
            this.closed = true;
        }

        this.executor.shutdown();
        try {
            if (!this.executor.awaitTermination(30, TimeUnit.SECONDS)) {
                FuckSable.LOGGER.warn("Async sub-level I/O executor '{}' did not terminate within 30s", this.name);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runSafely(final Runnable task) {
        CURRENT_QUEUE.set(this);
        try {
            task.run();
        } catch (final Throwable throwable) {
            FuckSable.LOGGER.error("Async sub-level disk IO task failed ({})", this.name, throwable);
        } finally {
            CURRENT_QUEUE.remove();
        }
    }
}
