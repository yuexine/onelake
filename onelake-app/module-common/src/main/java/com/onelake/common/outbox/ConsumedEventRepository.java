package com.onelake.common.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsumedEventRepository extends JpaRepository<ConsumedEvent, ConsumedEventId> {
}
