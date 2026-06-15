package com.onelake.common.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query("select e from OutboxEvent e where e.status = com.onelake.common.outbox.OutboxEvent$Status.PENDING order by e.occurredAt asc limit 100")
    List<OutboxEvent> findTop100Pending();
}
