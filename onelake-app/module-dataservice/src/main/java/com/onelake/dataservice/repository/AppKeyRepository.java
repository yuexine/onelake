package com.onelake.dataservice.repository;

import com.onelake.dataservice.domain.entity.AppKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AppKeyRepository extends JpaRepository<AppKey, UUID> {
    Optional<AppKey> findByAppKey(String appKey);
}
