package dev.fucksable.fix;

/**
 * {@code world-height-override} 的热路径缓存。
 * <p>
 * 为什么需要它：{@code LevelHeightAccessor.getMaxBuildHeight()}/{@code getMinBuildHeight()}
 * 是极热的方法——每个方块查询、每个粒子的 clip 遍历、每次 {@code isOutsideBuildHeight}
 * 都会调用。原实现每次都走 {@link FixRegistry#isEnabled(String)} 做 LinkedHashMap 查找，
 * 再读 options map，在粒子密集的场景下会被放大成明显的客户端卡顿
 * （tick 卡顿采样就落在 {@code fucksable$overrideMaxBuildHeight} 上）。
 * <p>
 * 这里把开关和两个高度值缓存成 volatile 字段，热路径上只剩两次 volatile 读。
 * 缓存由 {@link FixEntry} 在启用状态/选项变化时刷新，配置加载完成后也会刷新一次。
 */
public final class HeightOverrideState {

    private static volatile boolean enabled = false;
    private static volatile int maxBuildHeight = 320;
    private static volatile int minBuildHeight = -64;

    private HeightOverrideState() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static int maxBuildHeight() {
        return maxBuildHeight;
    }

    public static int minBuildHeight() {
        return minBuildHeight;
    }

    /**
     * 从 {@link FixRegistry} 重新读取开关和选项值。
     */
    public static void refresh() {
        final FixEntry entry = FixRegistry.getFix("world-height-override");
        maxBuildHeight = readInt(entry, "maxBuildHeight", 320);
        minBuildHeight = readInt(entry, "minBuildHeight", -64);
        enabled = entry != null && entry.isEnabled();
    }

    private static int readInt(FixEntry entry, String key, int fallback) {
        if (entry == null) {
            return fallback;
        }

        final Object value = entry.getOption(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
