package com.onelake.analytics.repository;

import com.onelake.analytics.domain.entity.DashboardPublication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface DashboardPublicationRepository extends JpaRepository<DashboardPublication, UUID> {

    Optional<DashboardPublication> findByDashboardIdAndIsCurrentTrue(UUID dashboardId);

    Optional<DashboardPublication> findByShareToken(String shareToken);

    /**
     * 把指定 dashboard 下所有 publication 的 is_current 置为 false。
     * 发布新版本前调用，配合 unique index 保证"每个 dashboard 同时仅一条 current"。
     */
    @Modifying
    @Query("update DashboardPublication p set p.isCurrent = false where p.dashboardId = :dashboardId")
    int clearCurrentForDashboard(UUID dashboardId);
}
