package com.plantogether.expense.event.publisher;

import com.plantogether.common.event.ExpenseCreatedEvent;
import com.plantogether.expense.config.RabbitConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
public class ExpenseEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final Counter publishFailures;

    public ExpenseEventPublisher(RabbitTemplate rabbitTemplate, MeterRegistry meterRegistry) {
        this.rabbitTemplate = rabbitTemplate;
        this.publishFailures = Counter.builder("expense_event_publish_failures_total")
                .description("Number of expense event publish failures (post-commit, broker-side)")
                .tag("service", "expense-service")
                .register(meterRegistry);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishExpenseCreated(ExpenseCreatedInternalEvent internal) {
        ExpenseCreatedEvent event = ExpenseCreatedEvent.builder()
                .expenseId(internal.expenseId())
                .tripId(internal.tripId())
                .paidByDeviceId(internal.paidByDeviceId())
                .amount(internal.amount())
                .description(internal.description())
                .createdAt(internal.createdAt())
                .build();
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.ROUTING_KEY_EXPENSE_CREATED, event);
        } catch (AmqpException ex) {
            publishFailures.increment();
            log.warn("Failed to publish expense.created (expenseId={}, tripId={}): {}",
                    internal.expenseId(), internal.tripId(), ex.getMessage(), ex);
        }
    }

    public record ExpenseCreatedInternalEvent(
            UUID expenseId,
            UUID tripId,
            String paidByDeviceId,
            BigDecimal amount,
            String description,
            Instant createdAt) {
    }
}
