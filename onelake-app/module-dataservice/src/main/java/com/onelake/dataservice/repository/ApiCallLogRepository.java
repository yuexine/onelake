package com.onelake.dataservice.repository;

import com.onelake.dataservice.domain.entity.ApiCallLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiCallLogRepository extends JpaRepository<ApiCallLog, Long> {
}
