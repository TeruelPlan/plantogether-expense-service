package com.plantogether.expense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.plantogether.common.exception.AccessDeniedException;
import com.plantogether.common.exception.ResourceNotFoundException;
import com.plantogether.common.grpc.Role;
import com.plantogether.common.grpc.TripClient;
import com.plantogether.common.grpc.TripMember;
import com.plantogether.common.grpc.TripMembership;
import com.plantogether.expense.domain.*;
import com.plantogether.expense.dto.ExpenseResponse;
import com.plantogether.expense.dto.RecordExpenseRequest;
import com.plantogether.expense.dto.UpdateExpenseRequest;
import com.plantogether.expense.event.publisher.ExpenseEventPublisher.ExpenseDeletedInternalEvent;
import com.plantogether.expense.fx.ExchangeRateProvider;
import com.plantogether.expense.fx.ExchangeRateProvider.FxQuote;
import com.plantogether.expense.repository.ExpenseRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class ExpenseUpdateDeleteServiceTest {

  @Mock private ExpenseRepository expenseRepository;
  @Mock private TripClient tripClient;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private ExchangeRateProvider exchangeRateProvider;

  private ExpenseService service;

  private static final UUID TRIP_ID = UUID.randomUUID();
  // Each "actor" has both a device id (caller's X-Device-Id) and a trip_member_id (the per-trip
  // membership row id). The membership gate now resolves the device id into a member id which is
  // what production stores and compares against expense.paidByTripMemberId.
  private static final String PAYER_DEVICE_ID = UUID.randomUUID().toString();
  private static final UUID PAYER_MEMBER_ID = UUID.randomUUID();
  private static final String OTHER_DEVICE_ID = UUID.randomUUID().toString();
  private static final UUID OTHER_MEMBER_ID = UUID.randomUUID();
  private static final String ORGANIZER_DEVICE_ID = UUID.randomUUID().toString();
  private static final UUID ORGANIZER_MEMBER_ID = UUID.randomUUID();
  private static final UUID EXPENSE_ID = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service =
        new ExpenseService(expenseRepository, tripClient, eventPublisher, exchangeRateProvider);
  }

  private Expense existingExpense() {
    Expense e =
        Expense.builder()
            .id(EXPENSE_ID)
            .tripId(TRIP_ID)
            .paidByTripMemberId(PAYER_MEMBER_ID)
            .amount(new BigDecimal("60.00"))
            .currency("EUR")
            .category(ExpenseCategory.FOOD)
            .description("Old description")
            .splitMode(SplitMode.EQUAL)
            .createdAt(Instant.now().minusSeconds(60))
            .updatedAt(Instant.now().minusSeconds(60))
            .exchangeRate(new BigDecimal("1.0000"))
            .amountInReferenceCurrency(new BigDecimal("60.0000"))
            .referenceCurrency("EUR")
            .rateSource(RateSource.LIVE)
            .rateFetchedAt(Instant.now().minusSeconds(60))
            .splits(new ArrayList<>())
            .build();
    e.addSplit(
        ExpenseSplit.builder()
            .tripMemberId(PAYER_MEMBER_ID)
            .shareAmount(new BigDecimal("20.00"))
            .build());
    e.addSplit(
        ExpenseSplit.builder()
            .tripMemberId(OTHER_MEMBER_ID)
            .shareAmount(new BigDecimal("20.00"))
            .build());
    e.addSplit(
        ExpenseSplit.builder()
            .tripMemberId(ORGANIZER_MEMBER_ID)
            .shareAmount(new BigDecimal("20.00"))
            .build());
    return e;
  }

  private UpdateExpenseRequest validUpdate() {
    return UpdateExpenseRequest.builder()
        .amount(new BigDecimal("90.00"))
        .currency("EUR")
        .category(ExpenseCategory.ACCOMMODATION)
        .description("New description")
        .splitMode(SplitMode.EQUAL)
        .build();
  }

  private void stubMembersAndFx() {
    when(tripClient.getTripMembers(TRIP_ID.toString()))
        .thenReturn(
            List.of(
                new TripMember("Payer", Role.PARTICIPANT, PAYER_MEMBER_ID.toString()),
                new TripMember("Other", Role.PARTICIPANT, OTHER_MEMBER_ID.toString()),
                new TripMember("Org", Role.ORGANIZER, ORGANIZER_MEMBER_ID.toString())));
    when(tripClient.getTripCurrency(TRIP_ID.toString())).thenReturn("EUR");
    when(exchangeRateProvider.getRate("EUR", "EUR"))
        .thenReturn(new FxQuote(new BigDecimal("1.0000"), RateSource.LIVE, Instant.now()));
  }

  // ────────────────── update ──────────────────

  @Test
  void update_byPayer_savesAndReturnsResponse() {
    when(expenseRepository.findByIdAndDeletedAtIsNull(EXPENSE_ID))
        .thenReturn(Optional.of(existingExpense()));
    when(tripClient.requireMembership(TRIP_ID.toString(), PAYER_DEVICE_ID))
        .thenReturn(new TripMembership(true, Role.PARTICIPANT, PAYER_MEMBER_ID.toString()));
    stubMembersAndFx();
    when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> inv.getArgument(0));

    ExpenseResponse resp = service.updateExpense(EXPENSE_ID, PAYER_DEVICE_ID, validUpdate());

    assertThat(resp.getAmount()).isEqualByComparingTo("90.00");
    assertThat(resp.getDescription()).isEqualTo("New description");
    assertThat(resp.getCategory()).isEqualTo(ExpenseCategory.ACCOMMODATION);
    // Membership gate is now mandatory even for the payer (former-member regression guard).
    verify(tripClient).requireMembership(TRIP_ID.toString(), PAYER_DEVICE_ID);
  }

  @Test
  void update_byFormerPayerRemovedFromTrip_throwsAccessDenied() {
    when(expenseRepository.findByIdAndDeletedAtIsNull(EXPENSE_ID))
        .thenReturn(Optional.of(existingExpense()));
    when(tripClient.requireMembership(TRIP_ID.toString(), PAYER_DEVICE_ID))
        .thenThrow(new AccessDeniedException("Unable to verify trip membership"));

    assertThatThrownBy(() -> service.updateExpense(EXPENSE_ID, PAYER_DEVICE_ID, validUpdate()))
        .isInstanceOf(AccessDeniedException.class);

    verify(expenseRepository, never()).save(any());
  }

  @Test
  void update_byOrganizer_savesAndReturnsResponse() {
    when(expenseRepository.findByIdAndDeletedAtIsNull(EXPENSE_ID))
        .thenReturn(Optional.of(existingExpense()));
    when(tripClient.requireMembership(TRIP_ID.toString(), ORGANIZER_DEVICE_ID))
        .thenReturn(new TripMembership(true, Role.ORGANIZER, ORGANIZER_MEMBER_ID.toString()));
    stubMembersAndFx();
    when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> inv.getArgument(0));

    ExpenseResponse resp = service.updateExpense(EXPENSE_ID, ORGANIZER_DEVICE_ID, validUpdate());

    assertThat(resp.getDescription()).isEqualTo("New description");
  }

  @Test
  void update_byOtherMemberNotOrganizer_throwsAccessDenied() {
    when(expenseRepository.findByIdAndDeletedAtIsNull(EXPENSE_ID))
        .thenReturn(Optional.of(existingExpense()));
    when(tripClient.requireMembership(TRIP_ID.toString(), OTHER_DEVICE_ID))
        .thenReturn(new TripMembership(true, Role.PARTICIPANT, OTHER_MEMBER_ID.toString()));

    assertThatThrownBy(() -> service.updateExpense(EXPENSE_ID, OTHER_DEVICE_ID, validUpdate()))
        .isInstanceOf(AccessDeniedException.class);

    verify(expenseRepository, never()).save(any());
  }

  @Test
  void update_byNonMember_throwsAccessDenied() {
    when(expenseRepository.findByIdAndDeletedAtIsNull(EXPENSE_ID))
        .thenReturn(Optional.of(existingExpense()));
    String stranger = UUID.randomUUID().toString();
    when(tripClient.requireMembership(TRIP_ID.toString(), stranger))
        .thenThrow(new AccessDeniedException("Unable to verify trip membership"));

    assertThatThrownBy(() -> service.updateExpense(EXPENSE_ID, stranger, validUpdate()))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void update_missingOrSoftDeleted_throwsResourceNotFound() {
    when(expenseRepository.findByIdAndDeletedAtIsNull(EXPENSE_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.updateExpense(EXPENSE_ID, PAYER_DEVICE_ID, validUpdate()))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void update_customSplitsMustSumToAmount() {
    when(expenseRepository.findByIdAndDeletedAtIsNull(EXPENSE_ID))
        .thenReturn(Optional.of(existingExpense()));
    when(tripClient.requireMembership(TRIP_ID.toString(), PAYER_DEVICE_ID))
        .thenReturn(new TripMembership(true, Role.PARTICIPANT, PAYER_MEMBER_ID.toString()));
    when(tripClient.getTripMembers(TRIP_ID.toString()))
        .thenReturn(
            List.of(
                new TripMember("Payer", Role.PARTICIPANT, PAYER_MEMBER_ID.toString()),
                new TripMember("Other", Role.PARTICIPANT, OTHER_MEMBER_ID.toString()),
                new TripMember("Org", Role.ORGANIZER, ORGANIZER_MEMBER_ID.toString())));

    UpdateExpenseRequest bad =
        UpdateExpenseRequest.builder()
            .amount(new BigDecimal("90.00"))
            .currency("EUR")
            .category(ExpenseCategory.FOOD)
            .description("desc")
            .splitMode(SplitMode.CUSTOM)
            .splits(
                List.of(
                    RecordExpenseRequest.SplitInput.builder()
                        .memberId(PAYER_MEMBER_ID)
                        .shareAmount(new BigDecimal("10.00"))
                        .build(),
                    RecordExpenseRequest.SplitInput.builder()
                        .memberId(OTHER_MEMBER_ID)
                        .shareAmount(new BigDecimal("10.00"))
                        .build()))
            .build();

    assertThatThrownBy(() -> service.updateExpense(EXPENSE_ID, PAYER_DEVICE_ID, bad))
        .hasMessageContaining("CUSTOM splits must sum to amount");
  }

  // ────────────────── delete ──────────────────

  @Test
  void delete_byPayer_softDeletesAndPublishesEvent() {
    Expense expense = existingExpense();
    when(expenseRepository.findByIdAndDeletedAtIsNull(EXPENSE_ID)).thenReturn(Optional.of(expense));
    when(tripClient.requireMembership(TRIP_ID.toString(), PAYER_DEVICE_ID))
        .thenReturn(new TripMembership(true, Role.PARTICIPANT, PAYER_MEMBER_ID.toString()));

    service.deleteExpense(EXPENSE_ID, PAYER_DEVICE_ID);

    assertThat(expense.getDeletedAt()).isNotNull();
    verify(expenseRepository).save(expense);

    ArgumentCaptor<ExpenseDeletedInternalEvent> evt =
        ArgumentCaptor.forClass(ExpenseDeletedInternalEvent.class);
    verify(eventPublisher).publishEvent(evt.capture());
    assertThat(evt.getValue().expenseId()).isEqualTo(EXPENSE_ID);
    assertThat(evt.getValue().tripId()).isEqualTo(TRIP_ID);
    assertThat(evt.getValue().paidByMemberId()).isEqualTo(PAYER_MEMBER_ID);
    assertThat(evt.getValue().deletedByMemberId()).isEqualTo(PAYER_MEMBER_ID);
  }

  @Test
  void delete_byOrganizer_softDeletes() {
    Expense expense = existingExpense();
    when(expenseRepository.findByIdAndDeletedAtIsNull(EXPENSE_ID)).thenReturn(Optional.of(expense));
    when(tripClient.requireMembership(TRIP_ID.toString(), ORGANIZER_DEVICE_ID))
        .thenReturn(new TripMembership(true, Role.ORGANIZER, ORGANIZER_MEMBER_ID.toString()));

    service.deleteExpense(EXPENSE_ID, ORGANIZER_DEVICE_ID);

    assertThat(expense.getDeletedAt()).isNotNull();
  }

  @Test
  void delete_byOtherMember_throwsAccessDenied() {
    Expense expense = existingExpense();
    when(expenseRepository.findByIdAndDeletedAtIsNull(EXPENSE_ID)).thenReturn(Optional.of(expense));
    when(tripClient.requireMembership(TRIP_ID.toString(), OTHER_DEVICE_ID))
        .thenReturn(new TripMembership(true, Role.PARTICIPANT, OTHER_MEMBER_ID.toString()));

    assertThatThrownBy(() -> service.deleteExpense(EXPENSE_ID, OTHER_DEVICE_ID))
        .isInstanceOf(AccessDeniedException.class);

    assertThat(expense.getDeletedAt()).isNull();
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void delete_alreadyDeleted_throwsResourceNotFound() {
    when(expenseRepository.findByIdAndDeletedAtIsNull(EXPENSE_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.deleteExpense(EXPENSE_ID, PAYER_DEVICE_ID))
        .isInstanceOf(ResourceNotFoundException.class);

    verify(eventPublisher, never()).publishEvent(any());
  }
}
