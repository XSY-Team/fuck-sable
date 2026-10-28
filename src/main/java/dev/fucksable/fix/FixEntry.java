package dev.fucksable.fix;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 单个修复项的注册条目。
 * <p>
 * 每个修复项有唯一ID、描述、默认启用状态、运行时启用状态、环境条件、端侧和自定义选项。
 * 端侧指定了修复项在哪个端生效：SERVER（仅服务端）、CLIENT（仅客户端）、BOTH（双端）。
 * 如果修复项标记为 SERVER，则在客户端不会启用；如果标记为 CLIENT，则在服务端不会启用。
 */
public class FixEntry {

    /**
     * 修复项的生效端侧。
     */
    public enum Side {
        /** 仅服务端生效 */
        SERVER,
        /** 仅客户端生效 */
        CLIENT,
        /** 双端都生效 */
        BOTH
    }

    private final String id;
    private final String description;
    private final boolean defaultEnabled;
    private final Set<String> requiredMods;
    private final Side side;
    private final boolean hidden;
    private boolean experimental;
    private boolean environmentMet;
    private boolean enabled;
    private final Map<String, Object> options;
    private final Map<String, Object> defaultOptions = new LinkedHashMap<>();

    FixEntry(String id, String description, boolean defaultEnabled, Set<String> requiredMods, Side side) {
        this(id, description, defaultEnabled, requiredMods, side, false);
    }

    FixEntry(String id, String description, boolean defaultEnabled, Set<String> requiredMods, Side side, boolean hidden) {
        this.id = id;
        this.description = description;
        this.defaultEnabled = defaultEnabled;
        this.requiredMods = requiredMods != null ? Set.copyOf(requiredMods) : Set.of();
        this.side = side != null ? side : Side.BOTH;
        this.hidden = hidden;
        this.environmentMet = true;
        this.enabled = defaultEnabled;
        this.options = new LinkedHashMap<>();
    }

    public String getId() { return id; }
    public String getDescription() { return description; }
    public boolean isDefaultEnabled() { return defaultEnabled; }
    public Set<String> getRequiredMods() { return requiredMods; }
    public Side getSide() { return side; }
    public boolean isHidden() { return hidden; }
    public boolean isExperimental() { return experimental; }
    public void setExperimental(boolean experimental) { this.experimental = experimental; }
    public boolean isEnvironmentMet() { return environmentMet; }

    /**
     * 设置环境条件是否满足。由FixRegistry在mod加载检测后调用。
     */
    void setEnvironmentMet(boolean met) { this.environmentMet = met; }

    /**
     * 修复项是否实际启用：需要运行时启用状态为true、环境条件满足、且端侧匹配。
     */
    public boolean isEnabled() { return enabled && environmentMet && isSideMatch(); }

    /**
     * 当前端是否匹配此修复项的端侧。
     */
    private boolean isSideMatch() {
        if (side == Side.BOTH) return true;
        boolean isClient = net.neoforged.api.distmarker.Dist.CLIENT == net.neoforged.fml.loading.FMLLoader.getDist();
        if (side == Side.SERVER) return !isClient;
        if (side == Side.CLIENT) return isClient;
        return true;
    }

    /**
     * 运行时启用状态（不考虑环境条件和端侧）。
     */
    public boolean isExplicitlyEnabled() { return enabled; }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.notifyDerivedState();
    }

    public Map<String, Object> getOptions() { return Collections.unmodifiableMap(options); }
    public void setOption(String key, Object value) { options.put(key, value); this.notifyDerivedState(); }
    public Object getOption(String key) { return options.get(key); }
    @SuppressWarnings("unchecked")
    public <T> T getOption(String key, T defaultValue) {
        Object v = options.get(key);
        if (v == null) return defaultValue;
        try { return (T) v; } catch (ClassCastException e) { return defaultValue; }
    }

    /**
     * 设置默认选项。同时写入defaultOptions和options（即当前值也变为默认值）。
     */
    public void setDefaultOption(String key, Object value) {
        defaultOptions.put(key, value);
        options.put(key, value);
        this.notifyDerivedState();
    }

    /**
     * 将options恢复为defaultOptions的副本。
     */
    public void resetOptions() {
        options.clear();
        options.putAll(defaultOptions);
        this.notifyDerivedState();
    }

    /**
     * 通知依赖本修复项状态的热路径缓存刷新（目前只有 world-height-override）。
     * <p>
     * 该修复项拦截的是 {@code LevelHeightAccessor.getMaxBuildHeight()} 这种每个方块查询
     * 都会调用的方法，热路径上不能做 map 查找，因此状态变化时主动刷新缓存。
     */
    private void notifyDerivedState() {
        if ("world-height-override".equals(this.id)) {
            HeightOverrideState.refresh();
        }
    }

    /**
     * 返回默认选项的不可修改视图。
     */
    public Map<String, Object> getDefaultOptions() { return Collections.unmodifiableMap(defaultOptions); }
}
