package com.plantogether.expense.event;

import static org.mockito.Mockito.verify;

import com.plantogether.expense.event.listener.ExpenseChangedListener;
import com.plantogether.expense.event.publisher.ExpenseEventPublisher.ExpenseCreatedInternalEvent;
import com.plantogether.expense.event.publisher.ExpenseEventPublisher.ExpenseDeletedInternalEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class ExpenseChangedListenerTest {

  @Mock private StringRedisTemplate redisTemplate;

  @InjectMocks private ExpenseChangedListener listener;

  private static final UUID TRIP_ID = UUID.randomUUID();

  @Test
  @DisplayName("onExpenseCreated invalidates the trip balance cache key")
  void onExpenseCreated_invalidatesCacheKey() {
    listener.onExpenseCreated(
        new ExpenseCreatedInternalEvent(
            UUID.randomUUID(),
            TRIP_ID,
            UUID.randomUUID().toString(),
            new BigDecimal("10.00"),
            "x",
            Instant.now()));

    verify(redisTemplate).delete("balance:" + TRIP_ID);
  }

  @Test
  @DisplayName("onExpenseDeleted invalidates the trip balance cache key")
  void onExpenseDeleted_invalidatesCacheKey() {
    listener.onExpenseDeleted(
        new ExpenseDeletedInternalEvent(
            UUID.randomUUID(), TRIP_ID, UUID.randomUUID(), UUID.randomUUID(), Instant.now()));

    verify(redisTemplate).delete("balance:" + TRIP_ID);
  }
}
