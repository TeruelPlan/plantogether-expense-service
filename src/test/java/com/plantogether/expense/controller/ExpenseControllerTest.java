package com.plantogether.expense.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plantogether.common.exception.AccessDeniedException;
import com.plantogether.common.grpc.TripClient;
import com.plantogether.common.security.SecurityAutoConfiguration;
import com.plantogether.expense.domain.ExpenseCategory;
import com.plantogether.expense.domain.RateSource;
import com.plantogether.expense.domain.SplitMode;
import com.plantogether.expense.dto.ExpenseResponse;
import com.plantogether.expense.exception.GlobalExceptionHandler;
import com.plantogether.expense.fx.ExchangeRateUnavailableException;
import com.plantogether.expense.service.ExpenseService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ExpenseController.class)
@Import({SecurityAutoConfiguration.class, GlobalExceptionHandler.class})
class ExpenseControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ExpenseService expenseService;

  @MockitoBean private TripClient tripClient;

  private final UUID deviceId = UUID.randomUUID();
  private final UUID tripId = UUID.randomUUID();

  @AfterEach
  void tearDown() {
    Mockito.reset(expenseService, tripClient);
  }

  private String validBody() {
    return """
    {
      "amount": 42.50,
      "currency": "EUR",
      "category": "FOOD",
      "description": "Team dinner",
      "splitMode": "EQUAL"
    }
    """;
  }

  private ExpenseResponse sampleResponse() {
    return ExpenseResponse.builder()
        .id(UUID.randomUUID())
        .tripId(tripId)
        .paidByDeviceId(deviceId)
        .amount(new BigDecimal("42.50"))
        .currency("EUR")
        .category(ExpenseCategory.FOOD)
        .description("Team dinner")
        .splitMode(SplitMode.EQUAL)
        .splits(List.of())
        .createdAt(Instant.now())
        .updatedAt(Instant.now())
        .exchangeRate(new BigDecimal("1.0000"))
        .amountInReferenceCurrency(new BigDecimal("42.5000"))
        .referenceCurrency("EUR")
        .rateSource(RateSource.LIVE)
        .rateFetchedAt(Instant.now())
        .build();
  }

  @Test
  void record_returns201_withValidBody() throws Exception {
    when(expenseService.recordExpense(eq(tripId), eq(deviceId.toString()), any()))
        .thenReturn(sampleResponse());

    mockMvc
        .perform(
            post("/api/v1/trips/{tripId}/expenses", tripId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(validBody()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.amount").value(42.50))
        .andExpect(jsonPath("$.description").value("Team dinner"));
  }

  @Test
  void record_returns400_withMissingAmount() throws Exception {
    String body =
        """
        {
          "currency": "EUR",
          "category": "FOOD",
          "description": "Dinner",
          "splitMode": "EQUAL"
        }
        """;

    mockMvc
        .perform(
            post("/api/v1/trips/{tripId}/expenses", tripId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void record_returns400_withZeroAmount() throws Exception {
    String body =
        """
        {
          "amount": 0,
          "currency": "EUR",
          "category": "FOOD",
          "description": "Dinner",
          "splitMode": "EQUAL"
        }
        """;

    mockMvc
        .perform(
            post("/api/v1/trips/{tripId}/expenses", tripId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void record_returns400_withNegativeAmount() throws Exception {
    String body =
        """
        {
          "amount": -5.00,
          "currency": "EUR",
          "category": "FOOD",
          "description": "Dinner",
          "splitMode": "EQUAL"
        }
        """;

    mockMvc
        .perform(
            post("/api/v1/trips/{tripId}/expenses", tripId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void record_returns400_withBlankDescription() throws Exception {
    String body =
        """
        {
          "amount": 10.00,
          "currency": "EUR",
          "category": "FOOD",
          "description": "",
          "splitMode": "EQUAL"
        }
        """;

    mockMvc
        .perform(
            post("/api/v1/trips/{tripId}/expenses", tripId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void record_returns400_withInvalidCurrency() throws Exception {
    String body =
        """
        {
          "amount": 10.00,
          "currency": "euro",
          "category": "FOOD",
          "description": "Dinner",
          "splitMode": "EQUAL"
        }
        """;

    mockMvc
        .perform(
            post("/api/v1/trips/{tripId}/expenses", tripId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void record_returns400_withMalformedJson() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/trips/{tripId}/expenses", tripId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content("{invalid json"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void record_returns403_forNonMember() throws Exception {
    when(expenseService.recordExpense(eq(tripId), eq(deviceId.toString()), any()))
        .thenThrow(new AccessDeniedException("Not a member"));

    mockMvc
        .perform(
            post("/api/v1/trips/{tripId}/expenses", tripId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(validBody()))
        .andExpect(status().isForbidden());
  }

  @Test
  void create_returns201_withAllFxFields_inResponse() throws Exception {
    when(expenseService.recordExpense(eq(tripId), eq(deviceId.toString()), any()))
        .thenReturn(sampleResponse());

    mockMvc
        .perform(
            post("/api/v1/trips/{tripId}/expenses", tripId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(validBody()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.exchangeRate").value(1.0000))
        .andExpect(jsonPath("$.amountInReferenceCurrency").value(42.5000))
        .andExpect(jsonPath("$.referenceCurrency").value("EUR"))
        .andExpect(jsonPath("$.rateSource").value("LIVE"))
        .andExpect(jsonPath("$.rateFetchedAt").exists());
  }

  @Test
  void create_returns400_withDisallowedCurrency() throws Exception {
    String body =
        """
        {
          "amount": 10.00,
          "currency": "BTC",
          "category": "FOOD",
          "description": "Coffee",
          "splitMode": "EQUAL"
        }
        """;

    mockMvc
        .perform(
            post("/api/v1/trips/{tripId}/expenses", tripId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void create_returns503_withProblemDetail_whenFxUnavailable() throws Exception {
    when(expenseService.recordExpense(eq(tripId), eq(deviceId.toString()), any()))
        .thenThrow(new ExchangeRateUnavailableException("USD", "EUR"));

    String body =
        """
        {
          "amount": 10.00,
          "currency": "USD",
          "category": "FOOD",
          "description": "Coffee",
          "splitMode": "EQUAL"
        }
        """;

    mockMvc
        .perform(
            post("/api/v1/trips/{tripId}/expenses", tripId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.title").value("Exchange rate unavailable"))
        .andExpect(jsonPath("$.baseCurrency").value("USD"))
        .andExpect(jsonPath("$.quoteCurrency").value("EUR"));
  }

  @Test
  void create_sameCurrency_returns201_withRateOneAndSourceLive() throws Exception {
    when(expenseService.recordExpense(eq(tripId), eq(deviceId.toString()), any()))
        .thenReturn(
            sampleResponse()); // sampleResponse already uses EUR/EUR → rate=1.0000, source=LIVE

    mockMvc
        .perform(
            post("/api/v1/trips/{tripId}/expenses", tripId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(validBody()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.exchangeRate").value(1.0000))
        .andExpect(jsonPath("$.rateSource").value("LIVE"))
        .andExpect(jsonPath("$.referenceCurrency").value("EUR"));
  }

  @Test
  void create_fallbackRate_returns201_withRateSourceFallback() throws Exception {
    Instant fallbackFetchedAt = Instant.parse("2026-04-01T10:00:00Z");
    ExpenseResponse fallbackResponse =
        ExpenseResponse.builder()
            .id(UUID.randomUUID())
            .tripId(tripId)
            .paidByDeviceId(deviceId)
            .amount(new BigDecimal("100.00"))
            .currency("USD")
            .category(ExpenseCategory.FOOD)
            .description("Lunch")
            .splitMode(SplitMode.EQUAL)
            .splits(List.of())
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .exchangeRate(new BigDecimal("0.9250"))
            .amountInReferenceCurrency(new BigDecimal("92.5000"))
            .referenceCurrency("EUR")
            .rateSource(RateSource.FALLBACK)
            .rateFetchedAt(fallbackFetchedAt)
            .build();
    when(expenseService.recordExpense(eq(tripId), eq(deviceId.toString()), any()))
        .thenReturn(fallbackResponse);

    String body =
        """
        {
          "amount": 100.00,
          "currency": "USD",
          "category": "FOOD",
          "description": "Lunch",
          "splitMode": "EQUAL"
        }
        """;

    mockMvc
        .perform(
            post("/api/v1/trips/{tripId}/expenses", tripId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.rateSource").value("FALLBACK"))
        .andExpect(jsonPath("$.exchangeRate").value(0.9250))
        .andExpect(jsonPath("$.rateFetchedAt").value("2026-04-01T10:00:00Z"));
  }

  @Test
  void list_returns200_pageShape_withExpenses() throws Exception {
    var page = new PageImpl<>(List.of(sampleResponse()), PageRequest.of(0, 20), 1);
    when(expenseService.listExpenses(eq(tripId), eq(deviceId.toString()), any())).thenReturn(page);

    mockMvc
        .perform(
            get("/api/v1/trips/{tripId}/expenses", tripId)
                .header("X-Device-Id", deviceId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.size").value(20));
  }

  @Test
  void list_returns200_emptyPage_whenNoExpenses() throws Exception {
    var page = new PageImpl<ExpenseResponse>(List.of(), PageRequest.of(0, 20), 0);
    when(expenseService.listExpenses(eq(tripId), eq(deviceId.toString()), any())).thenReturn(page);

    mockMvc
        .perform(
            get("/api/v1/trips/{tripId}/expenses", tripId)
                .header("X-Device-Id", deviceId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.content.length()").value(0))
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void list_returns403_forNonMember() throws Exception {
    when(expenseService.listExpenses(eq(tripId), eq(deviceId.toString()), any()))
        .thenThrow(new AccessDeniedException("Not a member"));

    mockMvc
        .perform(
            get("/api/v1/trips/{tripId}/expenses", tripId)
                .header("X-Device-Id", deviceId.toString()))
        .andExpect(status().isForbidden());
  }
}
