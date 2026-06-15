package com.onelake.security.repository;

import com.onelake.security.domain.entity.Secret;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SecretRepository extends JpaRepository<Secret, UUID> {
    Optional<Secret> findByRefKey(String refKey);
}
