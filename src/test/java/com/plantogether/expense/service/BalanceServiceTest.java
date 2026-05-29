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
import com.plantogether.expense.domain.SettlementTransfer;
import com.plantogether.expense.domain.SplitMode;
import com.plantogether.expense.dto.BalanceResponse;
import com.plantogether.expense.dto.MarkTransferDoneRequest;
import com.plantogether.expense.dto.SettlementTransferDto;
import com.plantogether.expense.dto.SettlementTransferResponse;
import com.plantogether.expense.repository.ExpenseRepository;
import com.plantogether.expense.repository.SettlementTransferRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
// Setup helpers create stubs not every test path hits.
@MockitoSettings(strictness = Strictness.LENIENT)
class BalanceServiceTest {

  @Mock private ExpenseRepository expenseRepository;
  @Mock private SettlementTransferRepository settlementTransferRepository;
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
            expenseRepository,
            settlementTransferRepository,
            new BalanceCalculator(),
            tripClient,
            redisTemplate,
            objectMapper);
  }

  // ---------------------------------------------------------------------------
  // Stubs / fixtures
  // ---------------------------------------------------------------------------

  private void stubMember() {
    when(tripClient.requireMembership(TRIP_ID.toString(), DEVICE_ID))
        .thenReturn(new TripMembership(true, Role.PARTICIPANT, PAYER.toString()));
  }

  private void stubCacheMiss() {
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("balance:" + TRIP_ID)).thenReturn(null);
    when(settlementTransferRepository.findByTripId(TRIP_ID)).thenReturn(List.of());
  }

  private void stubTripContext(String currency) {
    when(tripClient.getTripCurrency(TRIP_ID.toString())).thenReturn(currency);
    when(tripClient.getTripMembers(TRIP_ID.toString()))
        .thenReturn(
            List.of(
                new TripMember("Alice", Role.PARTICIPANT, PAYER.toString()),
                new TripMember("Bob", Role.PARTICIPANT, OTHER.toString())));
  }

  /** PAYER paid 100 EUR, split 50/50 -> OTHER owes PAYER 50.00. */
  private void stubSingleTransferPlan() {
    Expense e = expense(PAYER, "100.00", "EUR", "1.0000", "100.0000");
    addSplit(e, PAYER, "50.00");
    addSplit(e, OTHER, "50.00");
    when(expenseRepository.findAllByTripIdAndDeletedAtIsNull(TRIP_ID)).thenReturn(List.of(e));
  }

  private Expense expense(
      UUID paidBy, String amount, String currency, String exchangeRate, String amountRef) {
    return Expense.builder()
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
  }

  private void addSplit(Expense e, UUID member, String share) {
    e.addSplit(
        ExpenseSplit.builder().tripMemberId(member).shareAmount(new BigDecimal(share)).build());
  }

  private MarkTransferDoneRequest request(UUID from, UUID to, String amount) {
    return MarkTransferDoneRequest.builder()
        .fromMemberId(from)
        .toMemberId(to)
        .amount(new BigDecimal(amount))
        .currency("EUR")
        .build();
  }

  // ---------------------------------------------------------------------------
  // getBalance (5.4.1)
  // ---------------------------------------------------------------------------

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
    stubSingleTransferPlan();

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

  // ---------------------------------------------------------------------------
  // getBalance DONE overlay (5.4.2)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("getBalance flags a matching persisted transfer as DONE and sets allSettled")
  void getBalance_mergesPersistedTransfer_marksDoneAndAllSettled() {
    stubMember();
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("balance:" + TRIP_ID)).thenReturn(null);
    stubTripContext("EUR");
    stubSingleTransferPlan();

    SettlementTransfer persisted =
        SettlementTransfer.builder()
            .id(UUID.randomUUID())
            .tripId(TRIP_ID)
            .fromMemberId(OTHER)
            .toMemberId(PAYER)
            .amount(new BigDecimal("50.0000"))
            .currency("EUR")
            .settledByMemberId(OTHER)
            .settledAt(Instant.now())
            .createdAt(Instant.now())
            .build();
    when(settlementTransferRepository.findByTripId(TRIP_ID)).thenReturn(List.of(persisted));

    BalanceResponse result = service.getBalance(TRIP_ID, DEVICE_ID);

    assertThat(result.getSettlements()).hasSize(1);
    assertThat(result.getSettlements().get(0).getStatus())
        .isEqualTo(SettlementTransferDto.STATUS_DONE);
    assertThat(result.getSettlements().get(0).getSettledByMemberId()).isEqualTo(OTHER);
    assertThat(result.isAllSettled()).isTrue();
  }

  @Test
  @DisplayName("getBalance leaves an unmatched transfer PENDING and allSettled false")
  void getBalance_noPersistedTransfer_staysPending() {
    stubMember();
    stubCacheMiss();
    stubTripContext("EUR");
    stubSingleTransferPlan();

    BalanceResponse result = service.getBalance(TRIP_ID, DEVICE_ID);

    assertThat(result.getSettlements()).hasSize(1);
    assertThat(result.getSettlements().get(0).getStatus())
        .isEqualTo(SettlementTransferDto.STATUS_PENDING);
    assertThat(result.isAllSettled()).isFalse();
  }

  // ---------------------------------------------------------------------------
  // markTransferDone (5.4.2)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("markTransferDone persists an involved-party transfer and evicts the cache")
  void markTransferDone_involvedParty_persistsAndEvicts() {
    // Caller is OTHER (the debtor) -> involved party.
    when(tripClient.requireMembership(TRIP_ID.toString(), DEVICE_ID))
        .thenReturn(new TripMembership(true, Role.PARTICIPANT, OTHER.toString()));
    stubTripContext("EUR");
    stubSingleTransferPlan();
    when(settlementTransferRepository.findByTripIdAndFromMemberIdAndToMemberIdAndAmountAndCurrency(
            eq(TRIP_ID), eq(OTHER), eq(PAYER), any(), eq("EUR")))
        .thenReturn(Optional.empty());
    when(settlementTransferRepository.save(any(SettlementTransfer.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    SettlementTransferResponse response =
        service.markTransferDone(TRIP_ID, DEVICE_ID, request(OTHER, PAYER, "50.00"));

    assertThat(response.getFromMemberId()).isEqualTo(OTHER);
    assertThat(response.getToMemberId()).isEqualTo(PAYER);
    assertThat(response.getSettledByMemberId()).isEqualTo(OTHER);
    verify(settlementTransferRepository).save(any(SettlementTransfer.class));
    verify(redisTemplate).delete("balance:" + TRIP_ID);
  }

  @Test
  @DisplayName(
      "markTransferDone is idempotent: a second call returns the existing row, no new insert")
  void markTransferDone_alreadyPersisted_returnsExisting() {
    when(tripClient.requireMembership(TRIP_ID.toString(), DEVICE_ID))
        .thenReturn(new TripMembership(true, Role.PARTICIPANT, OTHER.toString()));
    stubTripContext("EUR");
    stubSingleTransferPlan();
    SettlementTransfer existing =
        SettlementTransfer.builder()
            .id(UUID.randomUUID())
            .tripId(TRIP_ID)
            .fromMemberId(OTHER)
            .toMemberId(PAYER)
            .amount(new BigDecimal("50.00"))
            .currency("EUR")
            .settledByMemberId(OTHER)
            .settledAt(Instant.now())
            .createdAt(Instant.now())
            .build();
    when(settlementTransferRepository.findByTripIdAndFromMemberIdAndToMemberIdAndAmountAndCurrency(
            eq(TRIP_ID), eq(OTHER), eq(PAYER), any(), eq("EUR")))
        .thenReturn(Optional.of(existing));

    SettlementTransferResponse response =
        service.markTransferDone(TRIP_ID, DEVICE_ID, request(OTHER, PAYER, "50.00"));

    assertThat(response.getId()).isEqualTo(existing.getId());
    verify(settlementTransferRepository, never()).save(any());
    verify(redisTemplate).delete("balance:" + TRIP_ID);
  }

  @Test
  @DisplayName("markTransferDone rejects a transfer absent from the current plan with 409")
  void markTransferDone_notInPlan_throwsConflict() {
    when(tripClient.requireMembership(TRIP_ID.toString(), DEVICE_ID))
        .thenReturn(new TripMembership(true, Role.PARTICIPANT, OTHER.toString()));
    stubTripContext("EUR");
    stubSingleTransferPlan();

    assertThatThrownBy(
            () -> service.markTransferDone(TRIP_ID, DEVICE_ID, request(OTHER, PAYER, "999.00")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409");
    verify(settlementTransferRepository, never()).save(any());
  }

  @Test
  @DisplayName("markTransferDone forbids an unrelated non-organizer caller (403)")
  void markTransferDone_unrelatedNonOrganizer_throwsAccessDenied() {
    UUID stranger = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    when(tripClient.requireMembership(TRIP_ID.toString(), DEVICE_ID))
        .thenReturn(new TripMembership(true, Role.PARTICIPANT, stranger.toString()));

    assertThatThrownBy(
            () -> service.markTransferDone(TRIP_ID, DEVICE_ID, request(OTHER, PAYER, "50.00")))
        .isInstanceOf(AccessDeniedException.class);
    verify(settlementTransferRepository, never()).save(any());
  }

  @Test
  @DisplayName("markTransferDone allows an organizer who is not an involved party")
  void markTransferDone_organizer_isAllowed() {
    UUID organizer = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
    when(tripClient.requireMembership(TRIP_ID.toString(), DEVICE_ID))
        .thenReturn(new TripMembership(true, Role.ORGANIZER, organizer.toString()));
    stubTripContext("EUR");
    stubSingleTransferPlan();
    when(settlementTransferRepository.findByTripIdAndFromMemberIdAndToMemberIdAndAmountAndCurrency(
            eq(TRIP_ID), eq(OTHER), eq(PAYER), any(), eq("EUR")))
        .thenReturn(Optional.empty());
    when(settlementTransferRepository.save(any(SettlementTransfer.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    SettlementTransferResponse response =
        service.markTransferDone(TRIP_ID, DEVICE_ID, request(OTHER, PAYER, "50.00"));

    assertThat(response.getSettledByMemberId()).isEqualTo(organizer);
    verify(settlementTransferRepository).save(any(SettlementTransfer.class));
  }
}
