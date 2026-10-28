package dev.fucksable.mixin;

import dev.fucksable.FuckSable;
import dev.fucksable.fix.FixEntry;
import dev.fucksable.fix.FixRegistry;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.border.WorldBorder;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 世界边界保护：阻止 SubLevel 掉出世界边界后永久丢失。
 * <p>
 * v1.8.0 重写原因（玩家被弹飞 / 结构抖动）：
 * {@code updatePose} 是每个物理子步（substep）都会调用的，而且它在 {@code readPose} 之后
 * 只是把物理引擎的 pose 抄进 {@code logicalPose()}。旧实现直接在 TAIL 里改
 * {@code logicalPose().position()}，物理引擎内部的刚体位置完全不知道这次改动，
 * 下一个 substep 又被 {@code readPose} 覆盖，于是每个 substep 都产生一次"逻辑位置跳变"。
 * <ul>
 *   <li>站在结构上或贴着结构的玩家，tracking point 每 substep 都在跳，最终被弹飞；</li>
 *   <li>手杖拖动时结构位置被反复改写，拖拽约束和实际刚体位置不一致，加剧弹飞；</li>
 *   <li>直接改 pose 不会真正让结构停下，因为物理速度没有被清零。</li>
 * </ul>
 * 现在改为：
 * <ul>
 *   <li>越界时通过 {@link RigidBodyHandle} 把物理线速度/角速度抵消掉，让物理引擎自己停住，
 *       不再每个 substep 改写 pose；</li>
 *   <li>只有越界深度超过 {@link #TELEPORT_DEPTH}（结构已经跑出很远、靠清速度救不回来）时，
 *       才用 {@code RigidBodyHandle.teleport} 把刚体传送回边界内，并对同一结构加
 *       {@link #TELEPORT_COOLDOWN_TICKS} 冷却，避免高频传送；</li>
 *   <li>刚体句柄不可用时才退化为直接改写 logicalPose（旧行为，至少不会丢结构）。</li>
 * </ul>
 */
@Mixin(SubLevelPhysicsSystem.class)
public class PhysicsStaffDragClipFixMixin {

    /** 越界深度超过该值才考虑传送回边界内（格）。 */
    @Unique
    private static final double TELEPORT_DEPTH = 16.0;

    /** 同一结构的两次传送之间至少间隔的 tick 数。 */
    @Unique
    private static final long TELEPORT_COOLDOWN_TICKS = 10L;

    @Unique
    private static final Map<UUID, Long> LAST_TELEPORT_TICK = new ConcurrentHashMap<>();

    @Shadow(remap = false)
    private ServerLevel level;

    @Inject(method = "updatePose", at = @At("TAIL"), remap = false)
    private void fucksable$clampSubLevelToWorldBounds(ServerSubLevel serverSubLevel, CallbackInfo ci) {
        if (!FixRegistry.isEnabled("physics-staff-drag-clipfix")) return;

        Vector3d position = serverSubLevel.logicalPose().position();
        WorldBorder border = this.level.getWorldBorder();

        double minX = border.getMinX() + 1.0;
        double maxX = border.getMaxX() - 1.0;
        double minZ = border.getMinZ() + 1.0;
        double maxZ = border.getMaxZ() - 1.0;

        double minY = this.level.getMinBuildHeight() + 1.0;
        double yMaxMargin = fucksable$getDoubleOption("yMaxMargin", 1000.0);
        double maxY = this.level.getMaxBuildHeight() + yMaxMargin;

        double overflowX = 0.0;
        double overflowY = 0.0;
        double overflowZ = 0.0;

        if (position.x < minX) overflowX = minX - position.x;
        else if (position.x > maxX) overflowX = position.x - maxX;

        if (position.y < minY) overflowY = minY - position.y;
        else if (position.y > maxY) overflowY = position.y - maxY;

        if (position.z < minZ) overflowZ = minZ - position.z;
        else if (position.z > maxZ) overflowZ = position.z - maxZ;

        if (overflowX <= 0.0 && overflowY <= 0.0 && overflowZ <= 0.0) {
            LAST_TELEPORT_TICK.remove(serverSubLevel.getUniqueId());
            return;
        }

        // 先把物理速度抵消掉，让物理引擎自己停在边界外一点，而不是每个 substep 改写位置
        fucksable$cancelVelocity(serverSubLevel);

        // 只是贴边越界一点点：不移动位置，避免玩家/乘客被高频传送弹飞
        double depth = Math.max(overflowX, Math.max(overflowY, overflowZ));
        if (depth <= TELEPORT_DEPTH) {
            return;
        }

        UUID subLevelId = serverSubLevel.getUniqueId();
        long gameTime = this.level.getGameTime();
        Long lastTeleport = LAST_TELEPORT_TICK.get(subLevelId);
        if (lastTeleport != null && gameTime - lastTeleport < TELEPORT_COOLDOWN_TICKS) {
            return;
        }
        LAST_TELEPORT_TICK.put(subLevelId, gameTime);

        double clampedX = Math.max(minX, Math.min(maxX, position.x));
        double clampedY = Math.max(minY, Math.min(maxY, position.y));
        double clampedZ = Math.max(minZ, Math.min(maxZ, position.z));

        if (!fucksable$teleport(serverSubLevel, new Vector3d(clampedX, clampedY, clampedZ))) {
            // 刚体句柄不可用：退化为直接改写逻辑位置，至少保证结构不会彻底丢失
            position.set(clampedX, clampedY, clampedZ);
            serverSubLevel.latestLinearVelocity.zero();
            serverSubLevel.latestAngularVelocity.zero();
        }

        FuckSable.LOGGER.warn(
            "SubLevel {} exceeded world bounds by {} blocks, moved back inside (further clamps within {} ticks are skipped)",
            subLevelId, String.format("%.1f", depth), TELEPORT_COOLDOWN_TICKS);
    }

    /**
     * 抵消物理刚体当前的速度，使其不再继续飞出边界。
     */
    @Unique
    private static void fucksable$cancelVelocity(ServerSubLevel serverSubLevel) {
        try {
            RigidBodyHandle handle = RigidBodyHandle.of(serverSubLevel);
            if (handle == null || !handle.isValid()) {
                return;
            }

            Vector3d linear = handle.getLinearVelocity(new Vector3d());
            Vector3d angular = handle.getAngularVelocity(new Vector3d());
            if (linear.lengthSquared() < 1.0E-9 && angular.lengthSquared() < 1.0E-9) {
                return;
            }

            handle.addLinearAndAngularVelocity(linear.negate(new Vector3d()), angular.negate(new Vector3d()));
        } catch (RuntimeException e) {
            FuckSable.LOGGER.debug("Failed to cancel velocity for sub-level {}", serverSubLevel.getUniqueId(), e);
        }
    }

    /**
     * 通过物理引擎把刚体传送回边界内，保证 logicalPose 与刚体位置不会脱节。
     *
     * @return 是否成功
     */
    @Unique
    private static boolean fucksable$teleport(ServerSubLevel serverSubLevel, Vector3d target) {
        try {
            RigidBodyHandle handle = RigidBodyHandle.of(serverSubLevel);
            if (handle == null || !handle.isValid()) {
                return false;
            }

            Quaterniondc orientation = serverSubLevel.logicalPose().orientation();
            handle.teleport(target, orientation);
            return true;
        } catch (RuntimeException e) {
            FuckSable.LOGGER.debug("Failed to teleport sub-level {} back into world bounds", serverSubLevel.getUniqueId(), e);
            return false;
        }
    }

    @Unique
    private static double fucksable$getDoubleOption(String key, double defaultValue) {
        FixEntry entry = FixRegistry.getFix("physics-staff-drag-clipfix");
        if (entry == null) return defaultValue;
        Object val = entry.getOption(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        if (val instanceof String) {
            try { return Double.parseDouble((String) val); } catch (NumberFormatException e) { return defaultValue; }
        }
        return defaultValue;
    }
}
