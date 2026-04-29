package com.plantogether.expense.service;

import com.plantogether.common.exception.AccessDeniedException;
import com.plantogether.common.grpc.Role;
import com.plantogether.common.grpc.TripClient;
import com.plantogether.common.grpc.TripMember;
import com.plantogether.expense.domain.Expense;
import com.plantogether.expense.domain.ExpenseCategory;
import com.plantogether.expense.domain.RateSource;
import com.plantogether.expense.domain.SplitMode;
import com.plantogether.expense.dto.ExpenseResponse;
import com.plantogether.expense.dto.RecordExpenseRequest;
import com.plantogether.expense.event.publisher.ExpenseEventPublisher.ExpenseCreatedInternalEvent;
import com.plantogether.expense.fx.ExchangeRateProvider;
import com.plantogether.expense.fx.ExchangeRateProvider.FxQuote;
import com.plantogether.expense.fx.ExchangeRateUnavailableException;
import com.plantogether.expense.repository.ExpenseRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExpenseServiceTest {

    @Mock
    private ExpenseRepository expenseRepository;
    @Mock
    private TripClient tripClient;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ExchangeRateProvider exchangeRateProvider;

    private ExpenseService service;

    private static final UUID TRIP_ID = UUID.randomUUID();
    private static final String DEVICE_ID = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        service = new ExpenseService(expenseRepository, tripClient, eventPublisher, exchangeRateProvider);
    }

    private void stubFxSameCurrency(String currency) {
        when(tripClient.getTripCurrency(TRIP_ID.toString())).thenReturn(currency);
        when(exchangeRateProvider.getRate(currency, currency))
                .thenReturn(new FxQuote(new BigDecimal("1.0000"), RateSource.LIVE, Instant.now()));
    }

    @Test
    void record_member_defaultEqualSplit_savesAndPublishesEvent() {
        UUID m1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID m2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID payer = UUID.fromString(DEVICE_ID);

        when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(true);
        when(tripClient.getTripMembers(TRIP_ID.toString())).thenReturn(List.of(
                new TripMember(m1, "Alice", Role.PARTICIPANT),
                new TripMember(m2, "Bob", Role.PARTICIPANT),
                new TripMember(payer, "Carol", Role.ORGANIZER)
        ));
        stubFxSameCurrency("EUR");
        when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> {
            Expense e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            e.setCreatedAt(Instant.now());
            e.setUpdatedAt(Instant.now());
            return e;
        });

        RecordExpenseRequest req = RecordExpenseRequest.builder()
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
        BigDecimal splitSum = saved.getSplits().stream()
                .map(s -> s.getShareAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(splitSum).isEqualByComparingTo(new BigDecimal("30.00"));

        ArgumentCaptor<ExpenseCreatedInternalEvent> eventCaptor = ArgumentCaptor.forClass(ExpenseCreatedInternalEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        ExpenseCreatedInternalEvent event = eventCaptor.getValue();
        assertThat(event.tripId()).isEqualTo(TRIP_ID);
        assertThat(event.paidByDeviceId()).isEqualTo(DEVICE_ID);
    }

    @Test
    void record_member_explicitSplits_passThroughs() {
        UUID splitDeviceId = UUID.fromString(DEVICE_ID);
        when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(true);
        when(tripClient.getTripMembers(TRIP_ID.toString())).thenReturn(List.of(
                new TripMember(splitDeviceId, "Alice", Role.PARTICIPANT)
        ));
        stubFxSameCurrency("EUR");
        when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> {
            Expense e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            e.setCreatedAt(Instant.now());
            e.setUpdatedAt(Instant.now());
            return e;
        });

        RecordExpenseRequest req = RecordExpenseRequest.builder()
                .amount(new BigDecimal("50.00"))
                .currency("EUR")
                .category(ExpenseCategory.TRANSPORT)
                .description("Taxi")
                .splitMode(SplitMode.CUSTOM)
                .splits(List.of(new RecordExpenseRequest.SplitInput(splitDeviceId, new BigDecimal("50.00"))))
                .build();

        service.recordExpense(TRIP_ID, DEVICE_ID, req);

        verify(expenseRepository).save(any(Expense.class));
    }

    @Test
    void record_nonMember_throwsAccessDenied() {
        when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(false);

        RecordExpenseRequest req = RecordExpenseRequest.builder()
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
        when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(true);

        RecordExpenseRequest req = RecordExpenseRequest.builder()
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
        UUID m1 = UUID.fromString(DEVICE_ID);
        when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(true);
        when(tripClient.getTripMembers(TRIP_ID.toString()))
                .thenReturn(List.of(new TripMember(m1, "Alice", Role.PARTICIPANT)));
        Instant now = Instant.parse("2026-04-28T10:00:00Z");
        when(tripClient.getTripCurrency(TRIP_ID.toString())).thenReturn("EUR");
        when(exchangeRateProvider.getRate("EUR", "EUR"))
                .thenReturn(new FxQuote(new BigDecimal("1.0000"), RateSource.LIVE, now));
        when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> {
            Expense e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            e.setCreatedAt(now);
            e.setUpdatedAt(now);
            return e;
        });

        RecordExpenseRequest req = RecordExpenseRequest.builder()
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
        UUID m1 = UUID.fromString(DEVICE_ID);
        when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(true);
        when(tripClient.getTripMembers(TRIP_ID.toString()))
                .thenReturn(List.of(new TripMember(m1, "Alice", Role.PARTICIPANT)));
        Instant fetchedAt = Instant.parse("2026-04-28T10:00:00Z");
        when(tripClient.getTripCurrency(TRIP_ID.toString())).thenReturn("EUR");
        when(exchangeRateProvider.getRate("USD", "EUR"))
                .thenReturn(new FxQuote(new BigDecimal("0.9220"), RateSource.LIVE, fetchedAt));
        when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> {
            Expense e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            e.setCreatedAt(fetchedAt);
            e.setUpdatedAt(fetchedAt);
            return e;
        });

        RecordExpenseRequest req = RecordExpenseRequest.builder()
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
        UUID m1 = UUID.fromString(DEVICE_ID);
        when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(true);
        when(tripClient.getTripMembers(TRIP_ID.toString()))
                .thenReturn(List.of(new TripMember(m1, "Alice", Role.PARTICIPANT)));
        Instant originalFetch = Instant.parse("2026-04-20T08:00:00Z");
        when(tripClient.getTripCurrency(TRIP_ID.toString())).thenReturn("EUR");
        when(exchangeRateProvider.getRate("USD", "EUR"))
                .thenReturn(new FxQuote(new BigDecimal("0.9100"), RateSource.FALLBACK, originalFetch));
        when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> {
            Expense e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            e.setCreatedAt(Instant.now());
            e.setUpdatedAt(Instant.now());
            return e;
        });

        RecordExpenseRequest req = RecordExpenseRequest.builder()
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
        UUID m1 = UUID.fromString(DEVICE_ID);
        when(tripClient.isMember(TRIP_ID.toString(), DEVICE_ID)).thenReturn(true);
        when(tripClient.getTripMembers(TRIP_ID.toString()))
                .thenReturn(List.of(new TripMember(m1, "Alice", Role.PARTICIPANT)));
        when(tripClient.getTripCurrency(TRIP_ID.toString())).thenReturn("EUR");
        when(exchangeRateProvider.getRate("USD", "EUR"))
                .thenThrow(new ExchangeRateUnavailableException("USD", "EUR"));

        RecordExpenseRequest req = RecordExpenseRequest.builder()
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
        Expense e = Expense.builder()
                .id(UUID.randomUUID())
                .tripId(tripId)
                .paidBy(UUID.fromString(DEVICE_ID))
                .amount(new BigDecimal("10.00"))
                .currency("EUR")
                .category(ExpenseCategory.FOOD)
                .description("Test")
                .splitMode(SplitMode.EQUAL)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .splits(new ArrayList<>())
                .build();
        return e;
    }
}
