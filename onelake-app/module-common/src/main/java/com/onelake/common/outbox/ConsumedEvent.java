package com.onelake.common.outbox;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "consumed_event", schema = "common")
@IdClass(ConsumedEventId.class)
@Getter
@Setter
public class ConsumedEvent {

    @Id
    private UUID eventId;

    @Id
    private String consumer;

    private Instant consumedAt = Instant.now();
}
