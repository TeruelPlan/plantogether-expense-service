package com.plantogether.expense.event.listener;

import com.plantogether.expense.event.publisher.ExpenseEventPublisher.ExpenseCreatedInternalEvent;
import com.plantogether.expense.event.publisher.ExpenseEventPublisher.ExpenseDeletedInternalEvent;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Invalidates the cached settlement balance and category breakdown whenever an expense changes.
 *
 * <p>Observes the same internal Spring application events fired by {@link
 * com.plantogether.expense.event.publisher.ExpenseEventPublisher}, running on {@code AFTER_COMMIT}
 * so the eviction never executes for a rolled-back write. This listener is the SOLE owner of these
 * caches' invalidation.
 *
 * <p>Note: the edit path (story 5.3) re-emits an {@link ExpenseCreatedInternalEvent} rather than a
 * dedicated update event, so it is covered here without a separate listener method.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpenseChangedListener {

  private static final String BALANCE_KEY_PREFIX = "balance:";
  private static final String BREAKDOWN_KEY_PREFIX = "breakdown:";

  private final StringRedisTemplate redisTemplate;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onExpenseCreated(ExpenseCreatedInternalEvent event) {
    evict(event.tripId());
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onExpenseDeleted(ExpenseDeletedInternalEvent event) {
    evict(event.tripId());
  }

  private void evict(UUID tripId) {
    redisTemplate.delete(BALANCE_KEY_PREFIX + tripId);
    redisTemplate.delete(BREAKDOWN_KEY_PREFIX + tripId);
    log.debug("Invalidated cached balance and breakdown for trip {}", tripId);
  }
}
