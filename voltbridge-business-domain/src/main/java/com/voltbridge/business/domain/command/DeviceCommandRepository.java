package com.voltbridge.business.domain.command;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 设备命令聚合的领域仓储端口。
 */
public interface DeviceCommandRepository {

    void save(DeviceCommand command);

    Optional<DeviceCommand> findById(UUID commandId);

    List<DeviceCommand> findSentExpiredBefore(Instant now);

    /** 查询同一业务对象上尚未结束的指定类型命令，用于控制重复操作。 */
    default Optional<DeviceCommand> findActiveByTypeAndBusinessRef(DeviceCommandType type,
                                                                    String businessRef) {
        return Optional.empty();
    }

    /**
     * 原子地创建命令；若同一业务对象已有活动命令，则返回既有命令。
     *
     * <p>持久化实现必须用数据库唯一约束处理并发竞争；默认实现仅供内存仓储和测试使用。</p>
     */
    default DeviceCommand saveOrGetActive(DeviceCommand candidate) {
        Optional<DeviceCommand> existing = findActiveByTypeAndBusinessRef(
                candidate.type(), candidate.businessRef());
        if (existing.isPresent()) {
            return existing.get();
        }
        save(candidate);
        return candidate;
    }
}
