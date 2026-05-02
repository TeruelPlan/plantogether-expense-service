package com.plantogether.expense.event;

import com.plantogether.common.event.ExpenseCreatedEvent;
import com.plantogether.expense.config.RabbitConfig;
import com.plantogether.expense.event.publisher.ExpenseEventPublisher;
import com.plantogether.expense.event.publisher.ExpenseEventPublisher.ExpenseCreatedInternalEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ExpenseEventPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    private ExpenseEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new ExpenseEventPublisher(rabbitTemplate, new SimpleMeterRegistry());
    }

    @Test
    void afterCommit_sendsExpenseCreatedEvent_withCorrectRoutingKey() {
        UUID expenseId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        String deviceId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        ExpenseCreatedInternalEvent internal =
                new ExpenseCreatedInternalEvent(
                        expenseId, tripId, deviceId, new BigDecimal("99.99"), "Dinner", now);

        publisher.publishExpenseCreated(internal);

        ArgumentCaptor<Object> messageCaptor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<String> exchangeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> routingKeyCaptor = ArgumentCaptor.forClass(String.class);

        verify(rabbitTemplate)
                .convertAndSend(
                        exchangeCaptor.capture(), routingKeyCaptor.capture(), messageCaptor.capture());

        assertThat(exchangeCaptor.getValue()).isEqualTo(RabbitConfig.EXCHANGE);
        assertThat(routingKeyCaptor.getValue()).isEqualTo(RabbitConfig.ROUTING_KEY_EXPENSE_CREATED);

        ExpenseCreatedEvent event = (ExpenseCreatedEvent) messageCaptor.getValue();
        assertThat(event.getExpenseId()).isEqualTo(expenseId);
        assertThat(event.getTripId()).isEqualTo(tripId);
        assertThat(event.getPaidByDeviceId()).isEqualTo(deviceId);
        assertThat(event.getPaidByDeviceId()).isInstanceOf(String.class);
        assertThat(event.getAmount()).isEqualByComparingTo(new BigDecimal("99.99"));
    }
}
