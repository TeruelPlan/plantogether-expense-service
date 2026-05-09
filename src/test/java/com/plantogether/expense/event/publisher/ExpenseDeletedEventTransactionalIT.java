package com.plantogether.expense.event.publisher;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.plantogether.common.event.ExpenseDeletedEvent;
import com.plantogether.expense.config.RabbitConfig;
import com.plantogether.expense.event.publisher.ExpenseEventPublisher.ExpenseDeletedInternalEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration test that verifies {@link ExpenseEventPublisher#publishExpenseDeleted} is wired via
 * {@link org.springframework.transaction.event.TransactionalEventListener} with {@code
 * AFTER_COMMIT} semantics. Concretely:
 *
 * <ul>
 *   <li>Within a successful transaction, the internal event is published but the broker call only
 *       fires <em>after</em> commit.
 *   <li>On rollback, the broker call must never fire.
 * </ul>
 *
 * <p>Uses a manual Spring context (no {@code @SpringBootTest}) to keep the test fast and
 * independent of broker / database infrastructure. A custom in-memory {@link
 * PlatformTransactionManager} drives commit/rollback callbacks so the
 * {@code @TransactionalEventListener} contract is exercised end to end.
 */
class ExpenseDeletedEventTransactionalIT {

  private AnnotationConfigApplicationContext ctx;
  private TransactionTemplate tx;
  private ApplicationEventPublisher publisher;
  private RabbitTemplate rabbit;

  @BeforeEach
  void setUp() {
    ctx = new AnnotationConfigApplicationContext(TestConfig.class);
    tx = ctx.getBean(TransactionTemplate.class);
    publisher = ctx.getBean(ApplicationEventPublisher.class);
    rabbit = ctx.getBean(RabbitTemplate.class);
  }

  @AfterEach
  void tearDown() {
    ctx.close();
  }

  @Test
  void publishOnlyAfterCommit() {
    UUID expenseId = UUID.randomUUID();
    UUID tripId = UUID.randomUUID();
    UUID payer = UUID.randomUUID();
    UUID deletedBy = UUID.randomUUID();
    Instant when = Instant.now();

    tx.executeWithoutResult(
        status -> {
          publisher.publishEvent(
              new ExpenseDeletedInternalEvent(expenseId, tripId, payer, deletedBy, when));
          // Inside the tx — the broker MUST NOT have been hit yet.
          verifyNoInteractions(rabbit);
        });

    // After commit — exactly one publish, with the canonical exchange + routing key.
    verify(rabbit, times(1))
        .convertAndSend(
            eq(RabbitConfig.EXCHANGE),
            eq(RabbitConfig.ROUTING_KEY_EXPENSE_DELETED),
            any(Object.class));
  }

  @Test
  void noPublishOnRollback() {
    UUID expenseId = UUID.randomUUID();
    UUID tripId = UUID.randomUUID();
    UUID payer = UUID.randomUUID();
    UUID deletedBy = UUID.randomUUID();
    Instant when = Instant.now();

    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      publisher.publishEvent(
                          new ExpenseDeletedInternalEvent(
                              expenseId, tripId, payer, deletedBy, when));
                      throw new RuntimeException("forced rollback");
                    }))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("forced rollback");

    verify(rabbit, never())
        .convertAndSend(any(String.class), any(String.class), any(ExpenseDeletedEvent.class));
  }

  /**
   * Minimal Spring context: a real {@link PlatformTransactionManager} (in-memory), the {@link
   * ExpenseEventPublisher} bean, and a Mockito-mocked {@link RabbitTemplate} so we can assert the
   * broker-side interaction without an embedded broker.
   */
  @Configuration
  static class TestConfig {

    @Bean
    PlatformTransactionManager transactionManager() {
      return new InMemoryTransactionManager();
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager tm) {
      return new TransactionTemplate(tm);
    }

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }

    @Bean
    RabbitTemplate rabbitTemplate() {
      return Mockito.mock(RabbitTemplate.class);
    }

    @Bean
    ExpenseEventPublisher expenseEventPublisher(
        RabbitTemplate rabbitTemplate, MeterRegistry meterRegistry) {
      return new ExpenseEventPublisher(rabbitTemplate, meterRegistry);
    }

    /**
     * Smoke listener that proves {@code @EventListener} (synchronous) sees the event regardless of
     * commit — useful to differentiate the AFTER_COMMIT listener from a plain listener if the test
     * ever drifts.
     */
    @Bean
    SyncProbe syncProbe() {
      return new SyncProbe();
    }
  }

  static class SyncProbe {
    int seen = 0;

    @EventListener(ExpenseDeletedInternalEvent.class)
    void onEvent(ExpenseDeletedInternalEvent evt) {
      seen++;
    }
  }

  /**
   * Trivial in-memory transaction manager that fires the {@code @TransactionalEventListener}
   * lifecycle callbacks. Avoids pulling in JPA / DataSource / Hibernate just to drive the
   * AFTER_COMMIT phase.
   */
  static class InMemoryTransactionManager extends AbstractPlatformTransactionManager {

    InMemoryTransactionManager() {
      setTransactionSynchronization(SYNCHRONIZATION_ALWAYS);
    }

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(
        Object transaction, org.springframework.transaction.TransactionDefinition definition) {
      // no-op
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      // no-op — synchronization callbacks fire AFTER_COMMIT around this.
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      // no-op
    }
  }
}
