package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.OperatorInstall;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 租户算子安装记录持久化访问接口。
 */
public interface OperatorInstallRepository extends JpaRepository<OperatorInstall, UUID> {

    /** 查询租户全部已安装算子。 */
    List<OperatorInstall> findByTenantId(UUID tenantId);

    /** 查询租户对确定算子的安装和固定版本信息。 */
    Optional<OperatorInstall> findByTenantIdAndOperatorId(UUID tenantId, UUID operatorId);
}
