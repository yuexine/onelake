package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.Operator;
import com.onelake.orchestration.domain.enums.OperatorScope;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 算子主表持久化访问接口。
 */
public interface OperatorRepository extends JpaRepository<Operator, UUID> {

    /** 查询某作用域下的平台级算子。 */
    List<Operator> findByScopeAndTenantIdIsNull(OperatorScope scope);

    /** 查询租户拥有的自定义/私有算子。 */
    List<Operator> findByTenantIdAndScopeIn(UUID tenantId, Collection<OperatorScope> scopes);

    /** 按稳定引用查询平台级算子。 */
    Optional<Operator> findByOperatorRefAndScopeAndTenantIdIsNull(String operatorRef, OperatorScope scope);

    /** 在允许的租户作用域内按稳定引用查询算子。 */
    Optional<Operator> findByTenantIdAndOperatorRefAndScopeIn(UUID tenantId, String operatorRef,
                                                              Collection<OperatorScope> scopes);

    /** 查询同一 ref 的所有作用域记录，供唯一性和冲突检查。 */
    List<Operator> findByOperatorRef(String operatorRef);
}
