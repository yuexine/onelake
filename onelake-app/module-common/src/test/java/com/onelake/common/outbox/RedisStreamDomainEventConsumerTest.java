package com.onelake.common.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisStreamDomainEventConsumerTest {

    @Test
    void initializesGroupOnceAndPollsWithoutBlocking() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        ConsumedEventRepository consumedRepo = mock(ConsumedEventRepository.class);
        DomainEventHandler handler = new DomainEventHandler() {
            @Override
            public Set<String> eventTypes() {
                return Set.of("pipeline.publish-approval.requested");
            }

            @Override
            public String consumerName() {
                return "test-consumer";
            }

            @Override
            public void handle(OutboxEvent event) {
                // No records are returned in this polling test.
            }
        };
        when(redis.execute(any(RedisScript.class), eq(List.of("stream:pipeline.publish-approval.requested")),
            eq("common"))).thenReturn(1L);
        when(redis.opsForStream()).thenReturn(streams);
        when(streams.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset[].class)))
            .thenReturn(List.of());
        RedisStreamDomainEventConsumer consumer =
            new RedisStreamDomainEventConsumer(redis, consumedRepo, List.of(handler));

        consumer.consume();
        consumer.consume();

        verify(redis, times(1)).execute(any(RedisScript.class),
            eq(List.of("stream:pipeline.publish-approval.requested")), eq("common"));
        org.mockito.ArgumentCaptor<StreamReadOptions> optionsCaptor =
            org.mockito.ArgumentCaptor.forClass(StreamReadOptions.class);
        verify(streams, times(4)).read(
            any(Consumer.class), optionsCaptor.capture(), any(StreamOffset[].class));
        assertThat(optionsCaptor.getAllValues()).allMatch(options -> !options.isBlocking());
    }
}
