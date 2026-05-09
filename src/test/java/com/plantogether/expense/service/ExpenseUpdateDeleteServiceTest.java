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
  private static final UUID PAYER_ID = UUID.randomUUID();
  private static final UUID OTHER_MEMBER_ID = UUID.randomUUID();
  private static final UUID ORGANIZER_ID = UUID.randomUUID();
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
            .paidBy(PAYER_ID)
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
        ExpenseSplit.builder().deviceId(PAYER_ID).shareAmount(new BigDecimal("20.00")).build());
    e.addSplit(
        ExpenseSplit.builder()
            .deviceId(OTHER_MEMBER_ID)
            .shareAmount(new BigDecimal("20.00"))
            .build());
    e.addSplit(
        ExpenseSplit.builder().deviceId(ORGANIZER_ID).shareAmount(new BigDecimal("20.00")).build());
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
                new TripMember(PAYER_ID, "Payer", Role.PARTICIPANT),
                new TripMember(OTHER_MEMBER_ID, "Other", Role.PARTICIPANT),
                new TripMember(ORGANIZER_ID, "Org", Role.ORGANIZER)));
    when(tripClient.getTripCurrency(TRIP_ID.toString())).thenReturn("EUR");
    when(exchangeRateProvider.getRate("EUR", "EUR"))
        .thenReturn(new FxQuote(new BigDecimal("1.0000"), RateSource.LIVE, Instant.now()));
  }

  // ────────────────── update ──────────────────

  @Test
  void update_byPayer_savesAndReturnsResponse() {
    when(expenseRepository.findByIdAndDeletedAtIsNull(EXPENSE_ID))
        .thenReturn(Optional.of(existingExpense()));
    when(tripClient.requireMembership(TRIP_ID.toString(), PAYER_ID.toString()))
        .thenReturn(new TripMembership(true, Role.PARTICIPANT));
    stubMembersAndFx();
    when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> inv.getArgument(0));

    ExpenseResponse resp = service.updateExpense(EXPENSE_ID, PAYER_ID.toString(), validUpdate());

    assertThat(resp.getAmount()).isEqualByComparingTo("90.00");
    assertThat(resp.getDescription()).isEqualTo("New description");
    assertThat(resp.getCategory()).isEqualTo(ExpenseCategory.ACCOMMODATION);
    // Membership gate is now mandatory even for the payer (former-member regression guard).
    verify(tripClient).requireMembership(TRIP_ID.toString(), PAYER_ID.toString());
  }

  @Test
  void update_byFormerPayerRemovedFromTrip_throwsAccessDenied() {
    when(expenseRepository.findByIdAndDeletedAtIsNull(EXPENSE_ID))
        .thenReturn(Optional.of(existingExpense()));
    when(tripClient.requireMembership(TRIP_ID.toString(), PAYER_ID.toString()))
        .thenThrow(new AccessDeniedException("Unable to verify trip membership"));

    assertThatThrownBy(() -> service.updateExpense(EXPENSE_ID, PAYER_ID.toString(), validUpdate()))
        .isInstanceOf(AccessDeniedException.class);

    verify(expenseRepository, never()).save(any());
  }

  @Test
  void update_byOrganizer_savesAndReturnsResponse() {
    when(expenseRepository.findByIdAndDeletedAtIsNull(EXPENSE_ID))
        .thenReturn(Optional.of(existingExpense()));
    when(tripClient.requireMembership(TRIP_ID.toString(), ORGANIZER_ID.toString()))
        .thenReturn(new TripMembership(true, Role.ORGANIZER));
    stubMembersAndFx();
    when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> inv.getArgument(0));

    ExpenseResponse resp =
        service.updateExpense(EXPENSE_ID, ORGANIZER_ID.toString(), validUpdate());

    assertThat(resp.getDescription()).isEqualTo("New description");
  }

  @Test
  void update_byOtherMemberNotOrganizer_throwsAccessDenied() {
    when(expenseRepository.findByIdAndDeletedAtIsNull(EXPENSE_ID))
        .thenReturn(Optional.of(existingExpense()));
    when(tripClient.requireMembership(TRIP_ID.toString(), OTHER_MEMBER_ID.toString()))
        .thenReturn(new TripMembership(true, Role.PARTICIPANT));

    assertThatThrownBy(
            () -> service.updateExpense(EXPENSE_ID, OTHER_MEMBER_ID.toString(), validUpdate()))
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

    assertThatThrownBy(() -> service.updateExpense(EXPENSE_ID, PAYER_ID.toString(), validUpdate()))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void update_customSplitsMustSumToAmount() {
    when(expenseRepository.findByIdAndDeletedAtIsNull(EXPENSE_ID))
        .thenReturn(Optional.of(existingExpense()));
    when(tripClient.getTripMembers(TRIP_ID.toString()))
        .thenReturn(
            List.of(
                new TripMember(PAYER_ID, "Payer", Role.PARTICIPANT),
                new TripMember(OTHER_MEMBER_ID, "Other", Role.PARTICIPANT),
                new TripMember(ORGANIZER_ID, "Org", Role.ORGANIZER)));

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
                        .deviceId(PAYER_ID)
                        .shareAmount(new BigDecimal("10.00"))
                        .build(),
                    RecordExpenseRequest.SplitInput.builder()
                        .deviceId(OTHER_MEMBER_ID)
                        .shareAmount(new BigDecimal("10.00"))
                        .build()))
            .build();

    assertThatThrownBy(() -> service.updateExpense(EXPENSE_ID, PAYER_ID.toString(), bad))
        .hasMessageContaining("CUSTOM splits must sum to amount");
  }

  // ────────────────── delete ──────────────────

  @Test
  void delete_byPayer_softDeletesAndPublishesEvent() {
    Expense expense = existingExpense();
    when(expenseRepository.findByIdAndDeletedAtIsNull(EXPENSE_ID)).thenReturn(Optional.of(expense));
    when(tripClient.requireMembership(TRIP_ID.toString(), PAYER_ID.toString()))
        .thenReturn(new TripMembership(true, Role.PARTICIPANT));

    service.deleteExpense(EXPENSE_ID, PAYER_ID.toString());

    assertThat(expense.getDeletedAt()).isNotNull();
    verify(expenseRepository).save(expense);

    ArgumentCaptor<ExpenseDeletedInternalEvent> evt =
        ArgumentCaptor.forClass(ExpenseDeletedInternalEvent.class);
    verify(eventPublisher).publishEvent(evt.capture());
    assertThat(evt.getValue().expenseId()).isEqualTo(EXPENSE_ID);
    assertThat(evt.getValue().tripId()).isEqualTo(TRIP_ID);
    assertThat(evt.getValue().paidByDeviceId()).isEqualTo(PAYER_ID);
    assertThat(evt.getValue().deletedByDeviceId()).isEqualTo(PAYER_ID);
  }

  @Test
  void delete_byOrganizer_softDeletes() {
    Expense expense = existingExpense();
    when(expenseRepository.findByIdAndDeletedAtIsNull(EXPENSE_ID)).thenReturn(Optional.of(expense));
    when(tripClient.requireMembership(TRIP_ID.toString(), ORGANIZER_ID.toString()))
        .thenReturn(new TripMembership(true, Role.ORGANIZER));

    service.deleteExpense(EXPENSE_ID, ORGANIZER_ID.toString());

    assertThat(expense.getDeletedAt()).isNotNull();
  }

  @Test
  void delete_byOtherMember_throwsAccessDenied() {
    Expense expense = existingExpense();
    when(expenseRepository.findByIdAndDeletedAtIsNull(EXPENSE_ID)).thenReturn(Optional.of(expense));
    when(tripClient.requireMembership(TRIP_ID.toString(), OTHER_MEMBER_ID.toString()))
        .thenReturn(new TripMembership(true, Role.PARTICIPANT));

    assertThatThrownBy(() -> service.deleteExpense(EXPENSE_ID, OTHER_MEMBER_ID.toString()))
        .isInstanceOf(AccessDeniedException.class);

    assertThat(expense.getDeletedAt()).isNull();
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void delete_alreadyDeleted_throwsResourceNotFound() {
    when(expenseRepository.findByIdAndDeletedAtIsNull(EXPENSE_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.deleteExpense(EXPENSE_ID, PAYER_ID.toString()))
        .isInstanceOf(ResourceNotFoundException.class);

    verify(eventPublisher, never()).publishEvent(any());
  }
}
