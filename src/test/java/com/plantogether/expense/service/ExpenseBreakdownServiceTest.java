package com.plantogether.expense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.plantogether.common.exception.AccessDeniedException;
import com.plantogether.common.grpc.TripClient;
import com.plantogether.expense.domain.ExpenseCategory;
import com.plantogether.expense.dto.BreakdownResponse;
import com.plantogether.expense.repository.ExpenseRepository;
import com.plantogether.expense.repository.ExpenseRepository.CategoryTotalProjection;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExpenseBreakdownServiceTest {

  @Mock private ExpenseRepository expenseRepository;
  @Mock private TripClient tripClient;
  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOps;

  private ExpenseBreakdownService service;

  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

  private static final UUID TRIP_ID = UUID.randomUUID();
  private static final String DEVICE_ID = UUID.randomUUID().toString();

  @BeforeEach
  void setUp() {
    service =
        new ExpenseBreakdownService(expenseRepository, tripClient, redisTemplate, objectMapper);
  }

  private void stubMemberAndCacheMiss() {
    when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(true);
    when(tripClient.getTripCurrency(TRIP_ID.toString())).thenReturn("EUR");
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("breakdown:" + TRIP_ID)).thenReturn(null);
  }

  private static CategoryTotalProjection projection(
      ExpenseCategory category, String total, long count) {
    return new CategoryTotalProjection() {
      @Override
      public ExpenseCategory getCategory() {
        return category;
      }

      @Override
      public BigDecimal getTotal() {
        return new BigDecimal(total);
      }

      @Override
      public long getExpenseCount() {
        return count;
      }
    };
  }

  @Test
  @DisplayName("getBreakdown throws AccessDenied for a non-member (403)")
  void getBreakdown_nonMember_throwsAccessDenied() {
    when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(false);

    assertThatThrownBy(() -> service.getBreakdown(TRIP_ID, DEVICE_ID))
        .isInstanceOf(AccessDeniedException.class);

    verify(expenseRepository, never()).aggregateByCategory(any());
  }

  @Test
  @DisplayName("getBreakdown returns categories sorted by total descending")
  void getBreakdown_multipleCategories_sortedByTotalDesc() {
    stubMemberAndCacheMiss();
    when(expenseRepository.aggregateByCategory(TRIP_ID))
        .thenReturn(
            List.of(
                projection(ExpenseCategory.TRANSPORT, "60.30", 2),
                projection(ExpenseCategory.FOOD, "182.50", 4),
                projection(ExpenseCategory.ACCOMMODATION, "160.00", 1)));

    BreakdownResponse result = service.getBreakdown(TRIP_ID, DEVICE_ID);

    assertThat(result.getTotalAmount()).isEqualByComparingTo("402.80");
    assertThat(result.getCategories()).hasSize(3);
    assertThat(result.getCategories().get(0).getCategory()).isEqualTo(ExpenseCategory.FOOD);
    assertThat(result.getCategories().get(1).getCategory())
        .isEqualTo(ExpenseCategory.ACCOMMODATION);
    assertThat(result.getCategories().get(2).getCategory()).isEqualTo(ExpenseCategory.TRANSPORT);
  }

  @Test
  @DisplayName("getBreakdown returns a single 100% category")
  void getBreakdown_singleCategory_returns100Percent() {
    stubMemberAndCacheMiss();
    when(expenseRepository.aggregateByCategory(TRIP_ID))
        .thenReturn(List.of(projection(ExpenseCategory.FOOD, "120.00", 3)));

    BreakdownResponse result = service.getBreakdown(TRIP_ID, DEVICE_ID);

    assertThat(result.getCategories()).hasSize(1);
    assertThat(result.getCategories().get(0).getPercentage()).isEqualByComparingTo("100.00");
  }

  @Test
  @DisplayName("getBreakdown returns an empty breakdown when there are no expenses")
  void getBreakdown_noExpenses_returnsEmpty() {
    stubMemberAndCacheMiss();
    when(expenseRepository.aggregateByCategory(TRIP_ID)).thenReturn(List.of());

    BreakdownResponse result = service.getBreakdown(TRIP_ID, DEVICE_ID);

    assertThat(result.getTotalAmount()).isEqualByComparingTo("0.00");
    assertThat(result.getCategories()).isEmpty();
  }

  @Test
  @DisplayName("getBreakdown reconciles thirds so percentages sum to exactly 100.00")
  void getBreakdown_thirds_percentagesSumTo100() {
    stubMemberAndCacheMiss();
    when(expenseRepository.aggregateByCategory(TRIP_ID))
        .thenReturn(
            List.of(
                projection(ExpenseCategory.FOOD, "10.00", 1),
                projection(ExpenseCategory.TRANSPORT, "10.00", 1),
                projection(ExpenseCategory.ACTIVITY, "10.00", 1)));

    BreakdownResponse result = service.getBreakdown(TRIP_ID, DEVICE_ID);

    BigDecimal sum =
        result.getCategories().stream()
            .map(c -> c.getPercentage())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(sum).isEqualByComparingTo("100.00");
  }

  @Test
  @DisplayName("getBreakdown caches the result with TTL 600s on a miss")
  void getBreakdown_cacheMiss_writesCache() {
    stubMemberAndCacheMiss();
    when(expenseRepository.aggregateByCategory(TRIP_ID))
        .thenReturn(List.of(projection(ExpenseCategory.FOOD, "10.00", 1)));

    service.getBreakdown(TRIP_ID, DEVICE_ID);

    ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
    verify(valueOps).set(eq("breakdown:" + TRIP_ID), anyString(), ttl.capture());
    assertThat(ttl.getValue()).isEqualTo(Duration.ofSeconds(600));
  }

  @Test
  @DisplayName("getBreakdown serves a cache hit without querying the repository")
  void getBreakdown_cacheHit_skipsRepository() throws Exception {
    when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(true);
    BreakdownResponse cached =
        BreakdownResponse.builder()
            .tripId(TRIP_ID)
            .referenceCurrency("EUR")
            .totalAmount(new BigDecimal("10.00"))
            .categories(List.of())
            .build();
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("breakdown:" + TRIP_ID)).thenReturn(objectMapper.writeValueAsString(cached));

    BreakdownResponse result = service.getBreakdown(TRIP_ID, DEVICE_ID);

    assertThat(result.getTripId()).isEqualTo(TRIP_ID);
    verify(expenseRepository, never()).aggregateByCategory(any());
  }

  @Test
  @DisplayName("reconcilePercentages distributes leftover cents by largest remainder")
  void reconcilePercentages_distributesLeftover() {
    List<BigDecimal> raw =
        List.of(
            new BigDecimal("33.333333"), new BigDecimal("33.333333"), new BigDecimal("33.333333"));

    List<BigDecimal> reconciled = ExpenseBreakdownService.reconcilePercentages(raw);

    BigDecimal sum = reconciled.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(sum).isEqualByComparingTo("100.00");
    // The leftover 0.01 goes to the first entry (ties broken by index).
    assertThat(reconciled.get(0)).isEqualByComparingTo("33.34");
    assertThat(reconciled.get(1)).isEqualByComparingTo("33.33");
    assertThat(reconciled.get(2)).isEqualByComparingTo("33.33");
  }

  @Test
  @DisplayName("reconcilePercentages leaves exact percentages untouched")
  void reconcilePercentages_exactValues_unchanged() {
    List<BigDecimal> raw =
        List.of(new BigDecimal("50.00"), new BigDecimal("25.00"), new BigDecimal("25.00"));

    List<BigDecimal> reconciled = ExpenseBreakdownService.reconcilePercentages(raw);

    assertThat(reconciled.get(0)).isEqualByComparingTo("50.00");
    assertThat(reconciled.get(1)).isEqualByComparingTo("25.00");
    assertThat(reconciled.get(2)).isEqualByComparingTo("25.00");
  }
}
