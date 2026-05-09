package com.plantogether.expense.event.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.plantogether.common.event.ExpenseDeletedEvent;
import com.plantogether.expense.config.RabbitConfig;
import com.plantogether.expense.event.publisher.ExpenseEventPublisher.ExpenseDeletedInternalEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class ExpenseDeletedEventPublisherTest {

  @Test
  void publishExpenseDeleted_sendsExpenseDeletedEventOnExchangeWithCorrectRoutingKey() {
    RabbitTemplate rabbit = Mockito.mock(RabbitTemplate.class);
    ExpenseEventPublisher publisher = new ExpenseEventPublisher(rabbit, new SimpleMeterRegistry());

    UUID expenseId = UUID.randomUUID();
    UUID tripId = UUID.randomUUID();
    UUID payer = UUID.randomUUID();
    UUID deletedBy = UUID.randomUUID();
    Instant when = Instant.now();

    publisher.publishExpenseDeleted(
        new ExpenseDeletedInternalEvent(expenseId, tripId, payer, deletedBy, when));

    ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
    verify(rabbit).convertAndSend(eq(RabbitConfig.EXCHANGE), eq("expense.deleted"), body.capture());

    assertThat(body.getValue()).isInstanceOf(ExpenseDeletedEvent.class);
    ExpenseDeletedEvent event = (ExpenseDeletedEvent) body.getValue();
    assertThat(event.getExpenseId()).isEqualTo(expenseId);
    assertThat(event.getTripId()).isEqualTo(tripId);
    assertThat(event.getPaidByDeviceId()).isEqualTo(payer);
    assertThat(event.getDeletedByDeviceId()).isEqualTo(deletedBy);
    assertThat(event.getDeletedAt()).isEqualTo(when);
  }
}
