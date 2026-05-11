package com.plantogether.expense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.plantogether.common.exception.AccessDeniedException;
import com.plantogether.common.grpc.Role;
import com.plantogether.common.grpc.TripClient;
import com.plantogether.common.grpc.TripMember;
import com.plantogether.common.grpc.TripMembership;
import com.plantogether.expense.domain.*;
import com.plantogether.expense.dto.ExpenseResponse;
import com.plantogether.expense.dto.RecordExpenseRequest;
import com.plantogether.expense.event.publisher.ExpenseEventPublisher.ExpenseCreatedInternalEvent;
import com.plantogether.expense.fx.ExchangeRateProvider;
import com.plantogether.expense.fx.ExchangeRateProvider.FxQuote;
import com.plantogether.expense.fx.ExchangeRateUnavailableException;
import com.plantogether.expense.repository.ExpenseRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ExpenseServiceTest {

  @Mock private ExpenseRepository expenseRepository;
  @Mock private TripClient tripClient;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private ExchangeRateProvider exchangeRateProvider;

  private ExpenseService service;

  private static final UUID TRIP_ID = UUID.randomUUID();
  private static final String DEVICE_ID = UUID.randomUUID().toString();
  // Per-trip member id assigned to the calling device. Used to populate requireMembership stubs
  // and to identify the payer in trip member lists (memberIds are what production stores now).
  private static final UUID CALLER_MEMBER_ID = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service =
        new ExpenseService(expenseRepository, tripClient, eventPublisher, exchangeRateProvider);
  }

  private void stubFxSameCurrency(String currency) {
    when(tripClient.getTripCurrency(TRIP_ID.toString())).thenReturn(currency);
    when(exchangeRateProvider.getRate(currency, currency))
        .thenReturn(new FxQuote(new BigDecimal("1.0000"), RateSource.LIVE, Instant.now()));
  }

  private TripMembership participantMembership(UUID memberId) {
    return new TripMembership(true, Role.PARTICIPANT, memberId.toString());
  }

  @Test
  void record_member_defaultEqualSplit_savesAndPublishesEvent() {
    UUID m1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID m2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

    when(tripClient.requireMembership(TRIP_ID.toString(), DEVICE_ID))
        .thenReturn(participantMembership(CALLER_MEMBER_ID));
    when(tripClient.getTripMembers(TRIP_ID.toString()))
        .thenReturn(
            List.of(
                new TripMember("Alice", Role.PARTICIPANT, m1.toString()),
                new TripMember("Bob", Role.PARTICIPANT, m2.toString()),
                new TripMember("Carol", Role.ORGANIZER, CALLER_MEMBER_ID.toString())));
    stubFxSameCurrency("EUR");
    when(expenseRepository.save(any(Expense.class)))
        .thenAnswer(
            inv -> {
              Expense e = inv.getArgument(0);
              e.setId(UUID.randomUUID());
              e.setCreatedAt(Instant.now());
              e.setUpdatedAt(Instant.now());
              return e;
            });

    RecordExpenseRequest req =
        RecordExpenseRequest.builder()
            .amount(new BigDecimal("30.00"))
            .currency("EUR")
            .category(ExpenseCategory.FOOD)
            .description("Dinner")
            .splitMode(SplitMode.EQUAL)
            .build();

    ExpenseResponse resp = service.recordExpense(TRIP_ID, DEVICE_ID, req);

    ArgumentCaptor<Expense> expenseCaptor = ArgumentCaptor.forClass(Expense.class);
    verify(expenseRepository).save(expenseCaptor.capture());
    Expense saved = expenseCaptor.getValue();

    assertThat(saved.getSplits()).hasSize(3);
    BigDecimal splitSum =
        saved.getSplits().stream()
            .map(ExpenseSplit::getShareAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(splitSum).isEqualByComparingTo(new BigDecimal("30.00"));

    ArgumentCaptor<ExpenseCreatedInternalEvent> eventCaptor =
        ArgumentCaptor.forClass(ExpenseCreatedInternalEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    ExpenseCreatedInternalEvent event = eventCaptor.getValue();
    assertThat(event.tripId()).isEqualTo(TRIP_ID);
    assertThat(event.paidByMemberId()).isEqualTo(CALLER_MEMBER_ID.toString());
  }

  @Test
  void record_member_explicitSplits_passThroughs() {
    UUID memberId = CALLER_MEMBER_ID;
    when(tripClient.requireMembership(TRIP_ID.toString(), DEVICE_ID))
        .thenReturn(participantMembership(memberId));
    when(tripClient.getTripMembers(TRIP_ID.toString()))
        .thenReturn(List.of(new TripMember("Alice", Role.PARTICIPANT, memberId.toString())));
    stubFxSameCurrency("EUR");
    when(expenseRepository.save(any(Expense.class)))
        .thenAnswer(
            inv -> {
              Expense e = inv.getArgument(0);
              e.setId(UUID.randomUUID());
              e.setCreatedAt(Instant.now());
              e.setUpdatedAt(Instant.now());
              return e;
            });

    RecordExpenseRequest req =
        RecordExpenseRequest.builder()
            .amount(new BigDecimal("50.00"))
            .currency("EUR")
            .category(ExpenseCategory.TRANSPORT)
            .description("Taxi")
            .splitMode(SplitMode.CUSTOM)
            .splits(List.of(new RecordExpenseRequest.SplitInput(memberId, new BigDecimal("50.00"))))
            .build();

    service.recordExpense(TRIP_ID, DEVICE_ID, req);

    verify(expenseRepository).save(any(Expense.class));
  }

  @Test
  void record_nonMember_throwsAccessDenied() {
    when(tripClient.requireMembership(TRIP_ID.toString(), DEVICE_ID))
        .thenThrow(new AccessDeniedException("Unable to verify trip membership"));

    RecordExpenseRequest req =
        RecordExpenseRequest.builder()
            .amount(new BigDecimal("10.00"))
            .currency("EUR")
            .category(ExpenseCategory.FOOD)
            .description("Snack")
            .splitMode(SplitMode.EQUAL)
            .build();

    assertThatThrownBy(() -> service.recordExpense(TRIP_ID, DEVICE_ID, req))
        .isInstanceOf(AccessDeniedException.class);

    verify(expenseRepository, never()).save(any());
  }

  @Test
  void record_splitModeCustomWithoutSplits_throws400() {
    when(tripClient.requireMembership(TRIP_ID.toString(), DEVICE_ID))
        .thenReturn(participantMembership(CALLER_MEMBER_ID));
    when(tripClient.getTripMembers(TRIP_ID.toString()))
        .thenReturn(
            List.of(new TripMember("Alice", Role.PARTICIPANT, CALLER_MEMBER_ID.toString())));

    RecordExpenseRequest req =
        RecordExpenseRequest.builder()
            .amount(new BigDecimal("20.00"))
            .currency("EUR")
            .category(ExpenseCategory.FOOD)
            .description("Lunch")
            .splitMode(SplitMode.CUSTOM)
            .build();

    assertThatThrownBy(() -> service.recordExpense(TRIP_ID, DEVICE_ID, req))
        .isInstanceOf(ResponseStatusException.class);

    verify(expenseRepository, never()).save(any());
  }

  @Test
  void list_member_returnsPageOrderedByCreatedAtDesc() {
    Expense e1 = buildExpense(TRIP_ID);
    Expense e2 = buildExpense(TRIP_ID);
    PageRequest pageable = PageRequest.of(0, 20);

    when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(true);
    when(expenseRepository.findByTripIdAndDeletedAtIsNull(TRIP_ID, pageable))
        .thenReturn(new PageImpl<>(List.of(e1, e2), pageable, 2));

    var page = service.listExpenses(TRIP_ID, DEVICE_ID, pageable);

    assertThat(page.getContent()).hasSize(2);
    assertThat(page.getTotalElements()).isEqualTo(2);
    verify(expenseRepository).findByTripIdAndDeletedAtIsNull(TRIP_ID, pageable);
  }

  @Test
  void list_nonMember_throwsAccessDenied() {
    when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(false);

    assertThatThrownBy(() -> service.listExpenses(TRIP_ID, DEVICE_ID, PageRequest.of(0, 20)))
        .isInstanceOf(AccessDeniedException.class);

    verify(expenseRepository, never()).findByTripIdAndDeletedAtIsNull(any(), any());
  }

  @Test
  void create_sameCurrency_persistsRateOne_sourceLive() {
    UUID memberId = CALLER_MEMBER_ID;
    when(tripClient.requireMembership(TRIP_ID.toString(), DEVICE_ID))
        .thenReturn(participantMembership(memberId));
    when(tripClient.getTripMembers(TRIP_ID.toString()))
        .thenReturn(List.of(new TripMember("Alice", Role.PARTICIPANT, memberId.toString())));
    Instant now = Instant.parse("2026-04-28T10:00:00Z");
    when(tripClient.getTripCurrency(TRIP_ID.toString())).thenReturn("EUR");
    when(exchangeRateProvider.getRate("EUR", "EUR"))
        .thenReturn(new FxQuote(new BigDecimal("1.0000"), RateSource.LIVE, now));
    when(expenseRepository.save(any(Expense.class)))
        .thenAnswer(
            inv -> {
              Expense e = inv.getArgument(0);
              e.setId(UUID.randomUUID());
              e.setCreatedAt(now);
              e.setUpdatedAt(now);
              return e;
            });

    RecordExpenseRequest req =
        RecordExpenseRequest.builder()
            .amount(new BigDecimal("42.0000"))
            .currency("EUR")
            .category(ExpenseCategory.FOOD)
            .description("Same currency")
            .splitMode(SplitMode.EQUAL)
            .build();

    ExpenseResponse resp = service.recordExpense(TRIP_ID, DEVICE_ID, req);

    assertThat(resp.getExchangeRate()).isEqualByComparingTo("1.0000");
    assertThat(resp.getAmountInReferenceCurrency()).isEqualByComparingTo("42.0000");
    assertThat(resp.getReferenceCurrency()).isEqualTo("EUR");
    assertThat(resp.getRateSource()).isEqualTo(RateSource.LIVE);
    assertThat(resp.getRateFetchedAt()).isEqualTo(now);
  }

  @Test
  void create_foreignCurrency_persistsConvertedAmount_rateSnapshot() {
    UUID memberId = CALLER_MEMBER_ID;
    when(tripClient.requireMembership(TRIP_ID.toString(), DEVICE_ID))
        .thenReturn(participantMembership(memberId));
    when(tripClient.getTripMembers(TRIP_ID.toString()))
        .thenReturn(List.of(new TripMember("Alice", Role.PARTICIPANT, memberId.toString())));
    Instant fetchedAt = Instant.parse("2026-04-28T10:00:00Z");
    when(tripClient.getTripCurrency(TRIP_ID.toString())).thenReturn("EUR");
    when(exchangeRateProvider.getRate("USD", "EUR"))
        .thenReturn(new FxQuote(new BigDecimal("0.9220"), RateSource.LIVE, fetchedAt));
    when(expenseRepository.save(any(Expense.class)))
        .thenAnswer(
            inv -> {
              Expense e = inv.getArgument(0);
              e.setId(UUID.randomUUID());
              e.setCreatedAt(fetchedAt);
              e.setUpdatedAt(fetchedAt);
              return e;
            });

    RecordExpenseRequest req =
        RecordExpenseRequest.builder()
            .amount(new BigDecimal("42.0000"))
            .currency("USD")
            .category(ExpenseCategory.FOOD)
            .description("Foreign")
            .splitMode(SplitMode.EQUAL)
            .build();

    ExpenseResponse resp = service.recordExpense(TRIP_ID, DEVICE_ID, req);

    ArgumentCaptor<Expense> cap = ArgumentCaptor.forClass(Expense.class);
    verify(expenseRepository).save(cap.capture());
    Expense saved = cap.getValue();

    assertThat(saved.getCurrency()).isEqualTo("USD");
    assertThat(saved.getReferenceCurrency()).isEqualTo("EUR");
    assertThat(saved.getExchangeRate()).isEqualByComparingTo("0.9220");
    assertThat(saved.getAmountInReferenceCurrency()).isEqualByComparingTo("38.7240");
    assertThat(saved.getRateSource()).isEqualTo(RateSource.LIVE);
    assertThat(resp.getAmountInReferenceCurrency()).isEqualByComparingTo("38.7240");
  }

  @Test
  void create_fallbackRate_persistsSourceFALLBACK_withOriginalFetchedAt() {
    UUID memberId = CALLER_MEMBER_ID;
    when(tripClient.requireMembership(TRIP_ID.toString(), DEVICE_ID))
        .thenReturn(participantMembership(memberId));
    when(tripClient.getTripMembers(TRIP_ID.toString()))
        .thenReturn(List.of(new TripMember("Alice", Role.PARTICIPANT, memberId.toString())));
    Instant originalFetch = Instant.parse("2026-04-20T08:00:00Z");
    when(tripClient.getTripCurrency(TRIP_ID.toString())).thenReturn("EUR");
    when(exchangeRateProvider.getRate("USD", "EUR"))
        .thenReturn(new FxQuote(new BigDecimal("0.9100"), RateSource.FALLBACK, originalFetch));
    when(expenseRepository.save(any(Expense.class)))
        .thenAnswer(
            inv -> {
              Expense e = inv.getArgument(0);
              e.setId(UUID.randomUUID());
              e.setCreatedAt(Instant.now());
              e.setUpdatedAt(Instant.now());
              return e;
            });

    RecordExpenseRequest req =
        RecordExpenseRequest.builder()
            .amount(new BigDecimal("10.0000"))
            .currency("USD")
            .category(ExpenseCategory.FOOD)
            .description("Fallback")
            .splitMode(SplitMode.EQUAL)
            .build();

    ExpenseResponse resp = service.recordExpense(TRIP_ID, DEVICE_ID, req);

    assertThat(resp.getRateSource()).isEqualTo(RateSource.FALLBACK);
    assertThat(resp.getRateFetchedAt()).isEqualTo(originalFetch);
  }

  @Test
  void create_rateUnavailable_propagatesException() {
    UUID memberId = CALLER_MEMBER_ID;
    when(tripClient.requireMembership(TRIP_ID.toString(), DEVICE_ID))
        .thenReturn(participantMembership(memberId));
    when(tripClient.getTripMembers(TRIP_ID.toString()))
        .thenReturn(List.of(new TripMember("Alice", Role.PARTICIPANT, memberId.toString())));
    when(tripClient.getTripCurrency(TRIP_ID.toString())).thenReturn("EUR");
    when(exchangeRateProvider.getRate("USD", "EUR"))
        .thenThrow(new ExchangeRateUnavailableException("USD", "EUR"));

    RecordExpenseRequest req =
        RecordExpenseRequest.builder()
            .amount(new BigDecimal("10.0000"))
            .currency("USD")
            .category(ExpenseCategory.FOOD)
            .description("Unavailable")
            .splitMode(SplitMode.EQUAL)
            .build();

    assertThatThrownBy(() -> service.recordExpense(TRIP_ID, DEVICE_ID, req))
        .isInstanceOf(ExchangeRateUnavailableException.class);

    verify(expenseRepository, never()).save(any());
  }

  private Expense buildExpense(UUID tripId) {
    return Expense.builder()
        .id(UUID.randomUUID())
        .tripId(tripId)
        .paidByTripMemberId(CALLER_MEMBER_ID)
        .amount(new BigDecimal("10.00"))
        .currency("EUR")
        .category(ExpenseCategory.FOOD)
        .description("Test")
        .splitMode(SplitMode.EQUAL)
        .createdAt(Instant.now())
        .updatedAt(Instant.now())
        .splits(new ArrayList<>())
        .build();
  }
}
