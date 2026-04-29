package com.plantogether.expense.grpc;

import com.plantogether.common.grpc.InProcessTripClient;
import com.plantogether.common.grpc.Role;
import com.plantogether.common.grpc.TripClientTestSupport;
import com.plantogether.common.grpc.TripMember;
import com.plantogether.expense.controller.ExpenseController;
import com.plantogether.expense.domain.Expense;
import com.plantogether.expense.domain.RateSource;
import com.plantogether.expense.event.publisher.ExpenseEventPublisher;
import com.plantogether.expense.exception.GlobalExceptionHandler;
import com.plantogether.expense.fx.ExchangeRateProvider;
import com.plantogether.expense.fx.ExchangeRateProvider.FxQuote;
import com.plantogether.expense.repository.ExpenseRepository;
import com.plantogether.expense.service.ExpenseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * In-process gRPC integration test exercising both {@code isMember} and
 * {@code getTripCurrency} through the shared {@link com.plantogether.common.grpc.TripClient}.
 */
class IsMemberAndCurrencyGateTest {

    private static final String DEVICE_ID = UUID.randomUUID().toString();
    private static final UUID TRIP_ID = UUID.randomUUID();

    private InProcessTripClient tripClient;
    private ExpenseRepository expenseRepository;
    private ExchangeRateProvider exchangeRateProvider;
    private MockMvc mockMvc;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        expenseRepository = mock(ExpenseRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        exchangeRateProvider = mock(ExchangeRateProvider.class);

        when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> {
            Expense e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            e.setCreatedAt(Instant.now());
            e.setUpdatedAt(Instant.now());
            return e;
        });

        when(exchangeRateProvider.getRate(any(), any()))
                .thenReturn(new FxQuote(new BigDecimal("0.9220"), RateSource.LIVE, Instant.now()));

        tripClient = TripClientTestSupport.builder()
                .member(TRIP_ID.toString(), DEVICE_ID)
                .withMembers(TRIP_ID.toString(), List.of(
                        new TripMember(UUID.fromString(DEVICE_ID), "Alice", Role.PARTICIPANT)
                ))
                .withCurrency(TRIP_ID.toString(), "EUR")
                .build();

        ExpenseService service = new ExpenseService(expenseRepository, tripClient, eventPublisher, exchangeRateProvider);
        ExpenseController controller = new ExpenseController(service);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        authentication = new UsernamePasswordAuthenticationToken(
                DEVICE_ID, "", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        SecurityContextHolder.clearContext();
        tripClient.close();
    }

    private String foreignCurrencyBody() {
        return """
                {
                  "amount": 42.00,
                  "currency": "USD",
                  "category": "FOOD",
                  "description": "Foreign meal",
                  "splitMode": "EQUAL"
                }
                """;
    }

    @Test
    void create_foreignCurrency_persistsReferenceCurrencyFromTripService() throws Exception {
        mockMvc.perform(post("/api/v1/trips/{tripId}/expenses", TRIP_ID)
                        .principal(authentication)
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(foreignCurrencyBody()))
                .andExpect(status().isCreated());

        ArgumentCaptor<Expense> cap = ArgumentCaptor.forClass(Expense.class);
        verify(expenseRepository).save(cap.capture());
        Expense saved = cap.getValue();

        assertThat(saved.getCurrency()).isEqualTo("USD");
        assertThat(saved.getReferenceCurrency()).isEqualTo("EUR");
        assertThat(saved.getExchangeRate()).isEqualByComparingTo("0.9220");
    }

    @Test
    void create_byNonMember_returns403_withNoDbWrite() throws Exception {
        UUID otherTrip = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/trips/{tripId}/expenses", otherTrip)
                        .principal(authentication)
                        .header("X-Device-Id", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(foreignCurrencyBody()))
                .andExpect(status().isForbidden());

        verify(expenseRepository, never()).save(any(Expense.class));
    }
}
