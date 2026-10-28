package dev.fucksable.debug;

import dev.fucksable.FuckSable;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 临时诊断工具：客户端 tick 卡住超过阈值时，打印渲染线程当前正在执行的调用栈。
 * <p>
 * TEMPORARY DIAGNOSTIC. 排查完毕后可直接删除本类、{@code TickStallWatcherMixin}
 * 以及 {@code fucksable.mixins.json} 中对应的条目。
 * <p>
 * 为什么不是"tick 结束后再打印"：tick 结束后只能拿到总耗时，卡顿瞬间的调用栈已经消失。
 * 所以这里用一个 daemon 线程在 tick 执行期间轮询，超过阈值就 dump 客户端主线程
 * （{@code Render thread}）的实时堆栈（和原版 watchdog 的思路一致，只是阈值低得多，
 * 也不会杀进程）。
 * <p>
 * 阈值可通过系统属性 {@code -Dfucksable.tickStallThresholdMs=1000} 调整。
 */
public final class TickStallWatcher {

    private static final long THRESHOLD_MS = Long.getLong("fucksable.tickStallThresholdMs", 1000L);
    private static final long REPEAT_INTERVAL_MS = Long.getLong("fucksable.tickStallRepeatMs", 5000L);
    private static final int MAX_STACK_FRAMES = 64;

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean TICKING = new AtomicBoolean(false);

    private static volatile long tickStartNanos;
    private static volatile long lastReportNanos;
    private static volatile Thread watchedThread;

    private TickStallWatcher() {
    }

    public static void onTickStart() {
        ensureStarted();
        watchedThread = Thread.currentThread();
        tickStartNanos = System.nanoTime();
        lastReportNanos = 0L;
        TICKING.set(true);
    }

    public static void onTickEnd() {
        TICKING.set(false);
    }

    private static void ensureStarted() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }

        final Thread watcher = new Thread(TickStallWatcher::watchLoop, "fuckSable Tick Stall Watcher");
        watcher.setDaemon(true);
        watcher.start();
        FuckSable.LOGGER.info("Tick stall watcher started (threshold {} ms, stack depth {})", THRESHOLD_MS, MAX_STACK_FRAMES);
    }

    private static void watchLoop() {
        while (true) {
            try {
                Thread.sleep(200L);
                if (!TICKING.get()) {
                    continue;
                }

                final long now = System.nanoTime();
                final long elapsedMs = (now - tickStartNanos) / 1_000_000L;
                if (elapsedMs < THRESHOLD_MS) {
                    continue;
                }

                final long last = lastReportNanos;
                if (last != 0L && (now - last) / 1_000_000L < REPEAT_INTERVAL_MS) {
                    continue;
                }
                lastReportNanos = now;

                // tick 可能刚好在这期间结束，避免误报
                if (!TICKING.get()) {
                    continue;
                }

                report(elapsedMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                FuckSable.LOGGER.warn("Tick stall watcher failed", t);
            }
        }
    }

    private static void report(long elapsedMs) {
        final Thread target = watchedThread;
        if (target == null) {
            return;
        }

        final ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        final ThreadInfo info = bean.getThreadInfo(target.threadId(), MAX_STACK_FRAMES);
        if (info == null) {
            FuckSable.LOGGER.warn("=== Tick stall: client tick running {} ms, but thread {} is gone ===", elapsedMs, target.getName());
            return;
        }

        final StringBuilder message = new StringBuilder(4096);
        message.append("\n=== Tick stall detected: client tick has been running for ")
            .append(elapsedMs)
            .append(" ms (threshold ")
            .append(THRESHOLD_MS)
            .append(" ms) ===\n");
        message.append("thread: ").append(info.getThreadName())
            .append(" | state: ").append(info.getThreadState());

        if (info.getLockName() != null) {
            message.append(" | waiting on: ").append(info.getLockName());
            if (info.getLockOwnerName() != null) {
                message.append(" held by: ").append(info.getLockOwnerName())
                    .append(" (id=").append(info.getLockOwnerId()).append(')');
            }
        }
        message.append('\n');

        final StackTraceElement[] stack = info.getStackTrace();
        if (stack.length == 0) {
            message.append("\t<empty stack>\n");
        } else {
            for (final StackTraceElement element : stack) {
                message.append("\tat ").append(element).append('\n');
            }
            if (stack.length >= MAX_STACK_FRAMES) {
                message.append("\t... truncated at ").append(MAX_STACK_FRAMES).append(" frames\n");
            }
        }

        message.append("=== end of tick stall report ===");
        FuckSable.LOGGER.warn(message.toString());
    }
}
