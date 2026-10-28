package dev.fucksable;

import dev.fucksable.command.FuckSableCommand;
import dev.fucksable.command.FuckSableLangCommand;
import dev.fucksable.fix.FixEntry;
import dev.fucksable.fix.FixRegistry;
import dev.fucksable.i18n.LanguageManager;
import dev.fucksable.update.UpdateChecker;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Mod(FuckSable.MOD_ID)
public class FuckSable {
    public static final String MOD_ID = "fucksable";
    public static final String VERSION = "1.7.25";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static FuckSableConfig config;
    private static ModContainer modContainer;

    public FuckSable(IEventBus bus, ModContainer container) {
        modContainer = container;

        // 1. 初始化i18n
        Path configDir = FMLPaths.CONFIGDIR.get();
        LanguageManager.init(configDir);

        // 2. 加载配置
        config = FuckSableConfig.load(configDir);

        // 3. 应用配置中的语言偏好
        LanguageManager.setLanguage(config.getLanguage());

        // 0. 启动动画（在配置加载后，以便判断彩蛋开关）
        printBanner(configDir);

        // 4. 注册内置修复项

        // === 核心修复 ===
        FixRegistry.register("async-save",
            "Redirects SubLevel save operations to an async I/O thread to prevent server freezes during saves",
            true, FixEntry.Side.BOTH);
        FixRegistry.register("panic-guard",
            "Adds safety checks before Rust native calls to prevent server crashes from panics in native code",
            true, FixEntry.Side.BOTH);
        FixRegistry.register("write-flush",
            "Ensures data is flushed to disk before updating storage file headers, preventing data corruption on crash",
            true, FixEntry.Side.BOTH);
        FixRegistry.register("corrupted-cleanup",
            "Removes corrupted sub-level pointers from holding chunks to prevent repeated load errors",
            true, FixEntry.Side.BOTH);

        // === 兼容性修复 ===
        FixRegistry.register("carryon-compat",
            "Fixes CarryOn placing players on physics sub-levels causing teleportation to wrong dimensions",
            true, Set.of("carryon"), FixEntry.Side.BOTH);
        FixRegistry.register("typewriter-server-fix",
            "Fixes Simulated mod typewriter crashing dedicated servers due to client-only class references in common code",
            true, Set.of("simulated"), FixEntry.Side.BOTH);
        FixRegistry.register("altitude-sensor-render-npe-fix",
            "Fixes client crash when rendering an Altitude Sensor on a contraption: Simulated 1.3.2 reads MovementContext.position without a null check, while Create's render-time context carries no world position",
            true, Set.of("simulated"), FixEntry.Side.CLIENT);
        FixRegistry.register("command-block-sublevel-fix",
            "Prevents command blocks (and variants) from being placed on physics sub-levels, which bypasses vanilla restrictions",
            true, Set.of("sable"), FixEntry.Side.BOTH);
        FixRegistry.register("aeronautics-server-fix",
            "Fixes Aeronautics SteamVentBlockEntity crashing dedicated servers due to client-only class references in common code",
            true, Set.of("aeronautics"), FixEntry.Side.BOTH);
        FixRegistry.register("aeronautics-slime-bearfix",
            "Fixes slime blocks sticking to bearing structures causing them to separate and clip through blocks",
            false, Set.of("aeronautics"), FixEntry.Side.BOTH);
        FixRegistry.register("physics-staff-drag-clipfix",
            "Prevents physics structures from clipping through physics blocks when dragged at high speed with the physics staff",
            true, Set.of("simulated"), FixEntry.Side.BOTH);
        FixRegistry.register("clip-ray-length-guard",
            "Drops raycasts longer than 1024 blocks: Sable's clip projects only the endpoint that lies inside a sub-level, so a ray starting on a physics structure and ending outside becomes millions of blocks long and freezes the tick in BlockGetter.traverseBlocks",
            true, Set.of("sable"), FixEntry.Side.BOTH);
        FixRegistry.register("plot-holder-guard",
            "Prevents server crash when block changes occur in plot chunks without a holder (e.g. bamboo growing near unloaded physics structures)",
            true, Set.of("sable"), FixEntry.Side.BOTH);
        FixRegistry.register("copycats-lift-compat",
            "Prevents server crash when Copycats blocks with missing facing property trigger sable$getNormal in onBlockChange",
            true, Set.of("sable", "copycats"), FixEntry.Side.BOTH);
        FixRegistry.register("player-position-guard",
            "Clamps player position to world border when X/Z coordinates exceed boundaries, preventing server crashes from SubLevel physics. Y coordinate is no longer limited.",
            true, Set.of("sable"), FixEntry.Side.BOTH);
        FixRegistry.register("light-engine-bounds-guard",
            "Prevents light engine crashes when SubLevel sections exceed world height limits during light propagation",
            true, Set.of("sable"), FixEntry.Side.BOTH);
        FixRegistry.register("physics-ticket-guard",
            "Prevents server crash when PhysicsChunkTicketManager triggers DistanceManager internal state corruption (ArrayIndexOutOfBoundsException in LeveledPriorityQueue)",
            true, Set.of("sable"), FixEntry.Side.BOTH);
        FixRegistry.register("sublevel-entity-guard",
            "Prevents server freeze when SubLevelInclusiveLevelEntityGetter iterates over abnormally large AABBs caused by corrupted entity section storage",
            true, Set.of("sable"), FixEntry.Side.BOTH);
        FixRegistry.register("sublevel-volume-limit",
            "Limits the maximum block count of a single physics structure to prevent server lag and Rapier native crashes from oversized collision bodies",
            true, Set.of("sable"), FixEntry.Side.BOTH);
        FixRegistry.register("ctt-concurrent-fix",
            "Fixes ConcurrentModificationException when CreateThreadedTrains ticks trains on worker threads while main thread modifies passenger data",
            true, Set.of("createthreadedtrains"), FixEntry.Side.BOTH);
        FixRegistry.register("ctt-log-spam-fix",
            "Suppresses repeated warning logs from CreateThreadedTrains when train calculation fails, only logs once per error type",
            true, Set.of("createthreadedtrains"), FixEntry.Side.BOTH);
        FixRegistry.register("create-trackgraph-null-guard",
            "Prevents server crash when Create train navigation searches with a null TrackNode (corrupted train state from CTT concurrent issues): TrackGraph.getConnectionsFrom returns empty Map instead of null to avoid NullPointerException in Navigation.search",
            true, Set.of("create"), FixEntry.Side.BOTH);
        FixRegistry.register("create-train-detach-nulledge-guard",
            "Prevents server crash when TrackGraph.removeNode triggers Train.detachFromTracks on a train with corrupted state (null TravellingPoint.edge): skips TrainMigration creation for points with null edge instead of throwing NullPointerException in TrainMigration constructor",
            true, Set.of("create"), FixEntry.Side.BOTH);
        FixRegistry.register("sublevel-load-log-spam-fix",
            "Throttles repeated 'Couldn't find sub-level' ERROR log spam from SubLevelStorage.attemptLoadSubLevel when a sub-level storage entry is corrupted/missing: logs once per chunk+index per 60s window instead of every retry",
            true, Set.of("sable"), FixEntry.Side.BOTH);
        FixRegistry.register("udp-invalid-packet-guard",
            "Silently drops UDP packets with invalid packet IDs (e.g. legacy server list ping packet ID 254) instead of letting SableUDPPacketDecoder throw IOException and spam 'Server UDP channel caught exception' ERROR logs",
            true, Set.of("sable"), FixEntry.Side.BOTH);
        FixRegistry.register("entity-lookup-remove-guard",
            "Catches ArrayIndexOutOfBoundsException thrown by Int2ObjectLinkedOpenHashMap inside EntityLookup.remove during PersistentEntitySectionManager.stopTracking, preventing single-entity removal failures from crashing the server tick loop when Sable corrupts the EntityLookup internal linked-map state",
            true, Set.of("sable"), FixEntry.Side.BOTH);
        FixEntry blockGuard = FixRegistry.register("block-destroy-coordinate-guard",
            "Prevents server crash and lag when modded items cause block destruction at integer-limit coordinates (Integer.MIN_VALUE/MAX_VALUE): blocks setBlock/destroyBlock calls when coordinates exceed world border limits (±30M) or Y bounds (-512~1024), preventing mass chunk loading and light propagation cascading",
            true, Set.of("sable"), FixEntry.Side.BOTH);
        blockGuard.setDefaultOption("xLimit", 30_000_000);
        blockGuard.setDefaultOption("yMin", -512);
        blockGuard.setDefaultOption("yMax", 1024);
        FixRegistry.register("frogport-extract-limit",
            "Prevents server freeze when FrogportBlockEntity.lazyTick pulls items from oversized adjacent inventories (hopper chains, Create warehouses): skips ItemHelper.extract when IItemHandler slot count exceeds 256, logs once per 60s",
            true, Set.of("create"), FixEntry.Side.BOTH);
        FixRegistry.register("ctt-posttick-timeout-guard",
            "Prevents Watchdog server crash when CreateThreadedTrains.postTick blocks the main thread waiting for a stuck async train worker: replaces Future.get() with a 10s timeout, cancels and skips on timeout to keep the server alive",
            true, Set.of("createthreadedtrains"), FixEntry.Side.BOTH);

        // === 客户端修复 ===
        FixRegistry.register("effortless-particle-fix",
            "Fixes Effortless client crash when interacting with Sable physics structures by skipping particle generation for unloaded chunks (Plot storage area coordinates)",
            true, Set.of("effortless", "sable"), FixEntry.Side.CLIENT);

        // === Vista 兼容修复 ===
        FixRegistry.register("vista-camera-chunk-fix",
            "Fixes Vista camera chunk loading incompatibility with Sable physics structures: projects ViewFinder SubLevel coordinates to world coordinates before force-loading chunks, preventing TPS drop and infinite loading loops",
            true, Set.of("vista", "sable"), FixEntry.Side.BOTH);

        // === ScalableLux 兼容修复 ===
        FixRegistry.register("sable-scalablelux-incompat-bypass",
            "Bypasses Sable's hardcoded incompatible-with-ScalableLux declaration via NeoForge's dependency override mechanism: writes 'dependencyOverrides.sable = [\"-scalablelux\"]' to config/fml.toml on startup. This is a prerequisite for 'scalablelux-compat'. Enable this first, then restart, before enabling scalablelux-compat.",
            false, Set.of("sable"), FixEntry.Side.BOTH);
        FixRegistry.register("scalablelux-compat",
            "Fixes Sable SubLevel lighting being completely disabled when ScalableLux is installed: ScalableLux clears the vanilla blockEngine/skyEngine fields of the main world light engine, causing Sable to misjudge SubLevel as having no block light and no sky light. Requires 'sable-scalablelux-incompat-bypass' to be enabled first.",
            true, Set.of("scalablelux", "sable"), FixEntry.Side.BOTH);

        // === 物理引擎修复 ===
        FixRegistry.register("constraint-self-fix",
            "Suppresses self-constraint errors in Sable physics pipeline: when a constraint is added between a SubLevel and itself, returns null instead of throwing IllegalArgumentException, preventing log spam",
            true, Set.of("sable"), FixEntry.Side.BOTH);

        // === 实验性修复 ===
        FixEntry worldHeightOverride = FixRegistry.register("world-height-override",
            "EXPERIMENTAL: Overrides vanilla world build height limits (getMaxBuildHeight/getMinBuildHeight). May cause incompatibility with other mods that depend on vanilla height. Use with caution.",
            false, null, FixEntry.Side.BOTH);
        worldHeightOverride.setDefaultOption("maxBuildHeight", 320);
        worldHeightOverride.setDefaultOption("minBuildHeight", -64);
        worldHeightOverride.setExperimental(true);

        // === 彩蛋 ===
        FixRegistry.register("fuck-op-player",
            "Easter egg: replaces the startup banner with 'fuck <random OP player name>' instead of 'FUCK SABLE'",
            false, null, FixEntry.Side.BOTH, true);

        // 5. 检测环境条件（前置mod是否加载）
        FixRegistry.checkEnvironment(modId -> {
            boolean loaded = net.neoforged.fml.loading.FMLLoader.getLoadingModList().getModFileById(modId) != null;
            if (!loaded) {
                LOGGER.info("Mod '{}' not found, related fixes will be disabled", modId);
            }
            return loaded;
        });

        // 6. 应用配置中的修复项状态
        for (FixEntry entry : FixRegistry.getAllFixes()) {
            Boolean state = config.getFixStates().get(entry.getId());
            if (state != null) {
                entry.setEnabled(state);
            }
        }

        // 6.1 应用配置中的修复项参数
        for (Map.Entry<String, Map<String, Object>> fixParamEntry : config.getFixParams().entrySet()) {
            FixEntry fixEntry = FixRegistry.getFix(fixParamEntry.getKey());
            if (fixEntry != null) {
                for (Map.Entry<String, Object> param : fixParamEntry.getValue().entrySet()) {
                    fixEntry.setOption(param.getKey(), param.getValue());
                }
            }
        }

        // 6.5 保存配置文件：首次启动生成新文件，版本升级时迁移补全新选项
        config.save(configDir);

        // 6.55 刷新热路径缓存（world-height-override 拦截的是每个方块查询都会调用的方法）
        dev.fucksable.fix.HeightOverrideState.refresh();

        // 6.6 自动添加 ScalableLux 依赖覆盖到 fml.toml
        if (FixRegistry.isEnabled("sable-scalablelux-incompat-bypass")) {
            ensureScalableLuxDependencyOverride(configDir);
        }

        // 7. 自动更新检查
        if (config.isAutoUpdate()) {
            UpdateChecker.checkAsync();
        }

        // 8. 注册事件监听
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);

        LOGGER.info("fuck Sable v{} loaded - {} fixes registered", VERSION, FixRegistry.getAllFixes().size());
    }

    private void printBanner(Path configDir) {
        // 检查配置中是否启用了彩蛋
        Boolean fuckOpState = config.getFixStates().get("fuck-op-player");
        boolean fuckOpEnabled = fuckOpState != null && fuckOpState;

        if (fuckOpEnabled) {
            // 从 ops.json 读取 OP 玩家名
            java.util.List<String> opNames = new java.util.ArrayList<>();
            try {
                // configDir 是 <server>/config，ops.json 在 <server>/ops.json
                var opsPath = configDir.getParent().resolve("ops.json");
                if (java.nio.file.Files.exists(opsPath)) {
                    var reader = java.nio.file.Files.newBufferedReader(opsPath);
                    var arr = com.google.gson.JsonParser.parseReader(reader).getAsJsonArray();
                    reader.close();
                    for (var elem : arr) {
                        var obj = elem.getAsJsonObject();
                        if (obj.has("name")) {
                            opNames.add(obj.get("name").getAsString());
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to read ops.json for fuck-op-player easter egg", e);
            }

            if (!opNames.isEmpty()) {
                String target = opNames.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(opNames.size()));
                ConsoleAnsiArtist.printAnsiText("FUCK " + target.toUpperCase(), "255,165,0", "");
                System.out.println();
                return;
            }
        }

        // 默认横幅
        ConsoleAnsiArtist.printAnsiText("FUCK SABLE", "255,80,80", "");
        System.out.println();
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        FuckSableCommand.register(event.getDispatcher());
        FuckSableLangCommand.register(event.getDispatcher());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        config.save(FMLPaths.CONFIGDIR.get());
    }

    /**
     * 自动检查 fml.toml 是否已有 ScalableLux 依赖覆盖，如果没有则添加。
     * <p>
     * NeoForge 在 ModSorter 阶段检查不兼容声明，时机早于 fs 构造器。
     * 因此 fs 无法在第一次同时安装 ScalableLux 时自动绕过——用户需要先安装 fs 并启动一次，
     * fs 会自动写入依赖覆盖，之后安装 ScalableLux 即可正常启动。
     */
    private static void ensureScalableLuxDependencyOverride(Path configDir) {
        try {
            Path fmlTomlPath = configDir.resolve("fml.toml");
            if (!Files.exists(fmlTomlPath)) {
                return; // fml.toml 不存在，让 NeoForge 自己创建
            }

            List<String> lines = new ArrayList<>(Files.readAllLines(fmlTomlPath, StandardCharsets.UTF_8));

            // 已有 scalablelux 相关的依赖覆盖，不需要添加
            for (String line : lines) {
                if (line.contains("scalablelux")) {
                    return;
                }
            }

            // 移除空的 dependencyOverrides = {} 行（NeoForge 默认值）
            lines.removeIf(line -> line.trim().equals("dependencyOverrides = {}"));

            // 检查是否已有 [dependencyOverrides] 节
            int sectionIndex = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().equals("[dependencyOverrides]")) {
                    sectionIndex = i;
                    break;
                }
            }

            if (sectionIndex >= 0) {
                // 在 [dependencyOverrides] 行后面添加 sable 行
                lines.add(sectionIndex + 1, "sable = [\"-scalablelux\"]");
            } else {
                // 文件末尾添加整个节
                if (!lines.isEmpty() && !lines.get(lines.size() - 1).trim().isEmpty()) {
                    lines.add(""); // 空行分隔
                }
                lines.add("[dependencyOverrides]");
                lines.add("sable = [\"-scalablelux\"]");
            }

            Files.write(fmlTomlPath, lines, StandardCharsets.UTF_8);
            LOGGER.info("Added ScalableLux dependency override to fml.toml (sable-scalablelux-incompat-bypass). Restart the game for the change to take effect.");
        } catch (Exception e) {
            LOGGER.warn("Failed to add ScalableLux dependency override to fml.toml", e);
        }
    }

    public static void saveConfig() {
        if (config != null) {
            config.save(FMLPaths.CONFIGDIR.get());
        }
    }

    public static ModContainer getModContainer() { return modContainer; }
}
