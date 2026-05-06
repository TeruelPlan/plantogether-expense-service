package com.plantogether.expense.event.publisher;

import com.plantogether.common.event.ExpenseCreatedEvent;
import com.plantogether.common.event.ExpenseDeletedEvent;
import com.plantogether.expense.config.RabbitConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
public class ExpenseEventPublisher {

  private final RabbitTemplate rabbitTemplate;
  private final Counter publishFailures;

  public ExpenseEventPublisher(RabbitTemplate rabbitTemplate, MeterRegistry meterRegistry) {
    this.rabbitTemplate = rabbitTemplate;
    this.publishFailures =
        Counter.builder("expense_event_publish_failures_total")
            .description("Number of expense event publish failures (post-commit, broker-side)")
            .tag("service", "expense-service")
            .register(meterRegistry);
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void publishExpenseCreated(ExpenseCreatedInternalEvent internal) {
    ExpenseCreatedEvent event =
        ExpenseCreatedEvent.builder()
            .expenseId(internal.expenseId())
            .tripId(internal.tripId())
            .paidByDeviceId(internal.paidByDeviceId())
            .amount(internal.amount())
            .description(internal.description())
            .createdAt(internal.createdAt())
            .build();
    publish(
        RabbitConfig.ROUTING_KEY_EXPENSE_CREATED, event, internal.expenseId(), internal.tripId());
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void publishExpenseDeleted(ExpenseDeletedInternalEvent internal) {
    ExpenseDeletedEvent event =
        ExpenseDeletedEvent.builder()
            .expenseId(internal.expenseId())
            .tripId(internal.tripId())
            .paidByDeviceId(internal.paidByDeviceId())
            .deletedByDeviceId(internal.deletedByDeviceId())
            .deletedAt(internal.deletedAt())
            .build();
    publish(
        RabbitConfig.ROUTING_KEY_EXPENSE_DELETED, event, internal.expenseId(), internal.tripId());
  }

  private void publish(String routingKey, Object event, UUID expenseId, UUID tripId) {
    try {
      rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, routingKey, event);
    } catch (AmqpException ex) {
      publishFailures.increment();
      log.warn(
          "Failed to publish {} (expenseId={}, tripId={}): {}",
          routingKey,
          expenseId,
          tripId,
          ex.getMessage(),
          ex);
    }
  }

  public record ExpenseCreatedInternalEvent(
      UUID expenseId,
      UUID tripId,
      String paidByDeviceId,
      BigDecimal amount,
      String description,
      Instant createdAt) {}

  public record ExpenseDeletedInternalEvent(
      UUID expenseId,
      UUID tripId,
      UUID paidByDeviceId,
      UUID deletedByDeviceId,
      Instant deletedAt) {}
}
