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
import com.plantogether.common.grpc.Role;
import com.plantogether.common.grpc.TripClient;
import com.plantogether.common.grpc.TripMember;
import com.plantogether.common.grpc.TripMembership;
import com.plantogether.expense.domain.Expense;
import com.plantogether.expense.domain.ExpenseCategory;
import com.plantogether.expense.domain.ExpenseSplit;
import com.plantogether.expense.domain.RateSource;
import com.plantogether.expense.domain.SplitMode;
import com.plantogether.expense.dto.BalanceResponse;
import com.plantogether.expense.repository.ExpenseRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
// Setup helpers (stubMember/stubCacheMiss/stubTripContext) create stubs not every test path hits.
@MockitoSettings(strictness = Strictness.LENIENT)
class BalanceServiceTest {

  @Mock private ExpenseRepository expenseRepository;
  @Mock private TripClient tripClient;
  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOps;

  private BalanceService service;

  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

  private static final UUID TRIP_ID = UUID.randomUUID();
  private static final String DEVICE_ID = UUID.randomUUID().toString();
  private static final UUID PAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

  @BeforeEach
  void setUp() {
    service =
        new BalanceService(
            expenseRepository, new BalanceCalculator(), tripClient, redisTemplate, objectMapper);
  }

  private void stubMember() {
    when(tripClient.requireMembership(TRIP_ID.toString(), DEVICE_ID))
        .thenReturn(new TripMembership(true, Role.PARTICIPANT, PAYER.toString()));
  }

  private void stubCacheMiss() {
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("balance:" + TRIP_ID)).thenReturn(null);
  }

  private void stubTripContext(String currency) {
    when(tripClient.getTripCurrency(TRIP_ID.toString())).thenReturn(currency);
    when(tripClient.getTripMembers(TRIP_ID.toString()))
        .thenReturn(
            List.of(
                new TripMember("Alice", Role.PARTICIPANT, PAYER.toString()),
                new TripMember("Bob", Role.PARTICIPANT, OTHER.toString())));
  }

  private Expense expense(
      UUID paidBy, String amount, String currency, String exchangeRate, String amountRef) {
    Expense e =
        Expense.builder()
            .id(UUID.randomUUID())
            .tripId(TRIP_ID)
            .paidByTripMemberId(paidBy)
            .amount(new BigDecimal(amount))
            .currency(currency)
            .category(ExpenseCategory.FOOD)
            .description("x")
            .splitMode(SplitMode.EQUAL)
            .exchangeRate(new BigDecimal(exchangeRate))
            .amountInReferenceCurrency(new BigDecimal(amountRef))
            .referenceCurrency("EUR")
            .rateSource(RateSource.LIVE)
            .rateFetchedAt(Instant.now())
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
    return e;
  }

  private void addSplit(Expense e, UUID member, String share) {
    e.addSplit(
        ExpenseSplit.builder().tripMemberId(member).shareAmount(new BigDecimal(share)).build());
  }

  @Test
  @DisplayName("getBalance throws AccessDenied for a non-member (403)")
  void getBalance_nonMember_throwsAccessDenied() {
    when(tripClient.requireMembership(TRIP_ID.toString(), DEVICE_ID))
        .thenThrow(new AccessDeniedException("Not a member"));

    assertThatThrownBy(() -> service.getBalance(TRIP_ID, DEVICE_ID))
        .isInstanceOf(AccessDeniedException.class);

    verify(expenseRepository, never()).findAllByTripIdAndDeletedAtIsNull(any());
  }

  @Test
  @DisplayName("getBalance serves a cache hit without recomputing")
  void getBalance_cacheHit_skipsComputation() throws Exception {
    stubMember();
    BalanceResponse cached =
        BalanceResponse.builder()
            .tripId(TRIP_ID)
            .referenceCurrency("EUR")
            .participantBalances(java.util.Map.of())
            .settlements(List.of())
            .computedAt(Instant.now())
            .build();
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("balance:" + TRIP_ID)).thenReturn(objectMapper.writeValueAsString(cached));

    BalanceResponse result = service.getBalance(TRIP_ID, DEVICE_ID);

    assertThat(result.getTripId()).isEqualTo(TRIP_ID);
    verify(expenseRepository, never()).findAllByTripIdAndDeletedAtIsNull(any());
  }

  @Test
  @DisplayName("getBalance computes on cache miss and writes the result with TTL 300s")
  void getBalance_cacheMiss_computesAndCaches() {
    stubMember();
    stubCacheMiss();
    stubTripContext("EUR");

    Expense e = expense(PAYER, "100.00", "EUR", "1.0000", "100.0000");
    addSplit(e, PAYER, "50.00");
    addSplit(e, OTHER, "50.00");
    when(expenseRepository.findAllByTripIdAndDeletedAtIsNull(TRIP_ID)).thenReturn(List.of(e));

    BalanceResponse result = service.getBalance(TRIP_ID, DEVICE_ID);

    assertThat(result.getSettlements()).hasSize(1);
    assertThat(result.getSettlements().get(0).getFromMemberId()).isEqualTo(OTHER);
    assertThat(result.getSettlements().get(0).getToMemberId()).isEqualTo(PAYER);
    assertThat(result.getSettlements().get(0).getAmount()).isEqualByComparingTo("50.00");

    ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
    verify(valueOps).set(eq("balance:" + TRIP_ID), anyString(), ttl.capture());
    assertThat(ttl.getValue()).isEqualTo(Duration.ofSeconds(300));
  }

  @Test
  @DisplayName("getBalance uses the stored reference amount, not a live conversion")
  void getBalance_usesStoredReferenceAmount() {
    stubMember();
    stubCacheMiss();
    stubTripContext("EUR");

    // Expense recorded in USD; stored amountInReferenceCurrency=92.50, rate=0.9250.
    Expense e = expense(PAYER, "100.00", "USD", "0.9250", "92.5000");
    addSplit(e, PAYER, "50.00"); // 50 USD -> 46.25 EUR
    addSplit(e, OTHER, "50.00");
    when(expenseRepository.findAllByTripIdAndDeletedAtIsNull(TRIP_ID)).thenReturn(List.of(e));

    BalanceResponse result = service.getBalance(TRIP_ID, DEVICE_ID);

    // PAYER paid 92.50 EUR, owes 46.25 -> net +46.25; OTHER owes 46.25 -> net -46.25.
    assertThat(result.getSettlements()).hasSize(1);
    assertThat(result.getSettlements().get(0).getAmount()).isEqualByComparingTo("46.25");
    assertThat(result.getReferenceCurrency()).isEqualTo("EUR");
  }

  @Test
  @DisplayName("getBalance falls back to EUR when the trip currency is unavailable")
  void getBalance_tripCurrencyUnavailable_fallsBackToEur() {
    stubMember();
    stubCacheMiss();
    when(tripClient.getTripCurrency(TRIP_ID.toString()))
        .thenThrow(
            new ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "down"));
    when(tripClient.getTripMembers(TRIP_ID.toString()))
        .thenReturn(List.of(new TripMember("Alice", Role.PARTICIPANT, PAYER.toString())));
    when(expenseRepository.findAllByTripIdAndDeletedAtIsNull(TRIP_ID)).thenReturn(List.of());

    BalanceResponse result = service.getBalance(TRIP_ID, DEVICE_ID);

    assertThat(result.getReferenceCurrency()).isEqualTo("EUR");
    assertThat(result.getSettlements()).isEmpty();
  }
}
