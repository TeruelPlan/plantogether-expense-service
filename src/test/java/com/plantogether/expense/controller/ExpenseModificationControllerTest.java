package com.plantogether.expense.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plantogether.common.exception.AccessDeniedException;
import com.plantogether.common.exception.ResourceNotFoundException;
import com.plantogether.common.grpc.TripClient;
import com.plantogether.common.security.SecurityAutoConfiguration;
import com.plantogether.expense.domain.ExpenseCategory;
import com.plantogether.expense.domain.RateSource;
import com.plantogether.expense.domain.SplitMode;
import com.plantogether.expense.dto.ExpenseResponse;
import com.plantogether.expense.exception.GlobalExceptionHandler;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ExpenseModificationController.class)
@Import({SecurityAutoConfiguration.class, GlobalExceptionHandler.class})
class ExpenseModificationControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ExpenseService expenseService;

  // Required because plantogether-common's grpc auto-configuration would otherwise wire a real
  // gRPC channel. Mocking it keeps @WebMvcTest hermetic.
  @MockitoBean private TripClient tripClient;

  private final UUID deviceId = UUID.randomUUID();
  private final UUID expenseId = UUID.randomUUID();
  private final UUID tripId = UUID.randomUUID();

  @AfterEach
  void tearDown() {
    Mockito.reset(expenseService, tripClient);
  }

  private String validBody() {
    return """
    {
      "amount": 90.00,
      "currency": "EUR",
      "category": "FOOD",
      "description": "Team dinner edited",
      "splitMode": "EQUAL"
    }
    """;
  }

  private ExpenseResponse fakeResponse() {
    return ExpenseResponse.builder()
        .id(expenseId)
        .tripId(tripId)
        .paidByMemberId(deviceId)
        .amount(new BigDecimal("90.00"))
        .currency("EUR")
        .category(ExpenseCategory.FOOD)
        .description("Team dinner edited")
        .splitMode(SplitMode.EQUAL)
        .splits(List.of())
        .createdAt(Instant.now())
        .updatedAt(Instant.now())
        .exchangeRate(new BigDecimal("1.0000"))
        .amountInReferenceCurrency(new BigDecimal("90.0000"))
        .referenceCurrency("EUR")
        .rateSource(RateSource.LIVE)
        .rateFetchedAt(Instant.now())
        .build();
  }

  @Test
  void update_returns200_withValidBody() throws Exception {
    when(expenseService.updateExpense(eq(expenseId), eq(deviceId.toString()), any()))
        .thenReturn(fakeResponse());

    mockMvc
        .perform(
            put("/api/v1/expenses/{id}", expenseId)
                .header("X-Device-Id", deviceId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.description").value("Team dinner edited"));
  }

  @Test
  void update_returns400_withBlankDescription() throws Exception {
    String body =
        """
        {
          "amount": 90.00,
          "currency": "EUR",
          "category": "FOOD",
          "description": "  ",
          "splitMode": "EQUAL"
        }
        """;
    mockMvc
        .perform(
            put("/api/v1/expenses/{id}", expenseId)
                .header("X-Device-Id", deviceId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void update_returns400_withNegativeAmount() throws Exception {
    String body =
        """
        {
          "amount": -1.00,
          "currency": "EUR",
          "category": "FOOD",
          "description": "x",
          "splitMode": "EQUAL"
        }
        """;
    mockMvc
        .perform(
            put("/api/v1/expenses/{id}", expenseId)
                .header("X-Device-Id", deviceId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void update_returns403_forNonPayerNonOrganizer() throws Exception {
    when(expenseService.updateExpense(eq(expenseId), eq(deviceId.toString()), any()))
        .thenThrow(new AccessDeniedException("forbidden"));

    mockMvc
        .perform(
            put("/api/v1/expenses/{id}", expenseId)
                .header("X-Device-Id", deviceId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isForbidden());
  }

  @Test
  void update_returns404_forMissingExpense() throws Exception {
    when(expenseService.updateExpense(eq(expenseId), eq(deviceId.toString()), any()))
        .thenThrow(new ResourceNotFoundException("Expense", expenseId));

    mockMvc
        .perform(
            put("/api/v1/expenses/{id}", expenseId)
                .header("X-Device-Id", deviceId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isNotFound());
  }

  @Test
  void delete_returns204_forPayer() throws Exception {
    mockMvc
        .perform(
            delete("/api/v1/expenses/{id}", expenseId).header("X-Device-Id", deviceId.toString()))
        .andExpect(status().isNoContent());
  }

  @Test
  void delete_returns403_forOtherMember() throws Exception {
    doThrow(new AccessDeniedException("forbidden"))
        .when(expenseService)
        .deleteExpense(expenseId, deviceId.toString());

    mockMvc
        .perform(
            delete("/api/v1/expenses/{id}", expenseId).header("X-Device-Id", deviceId.toString()))
        .andExpect(status().isForbidden());
  }

  @Test
  void delete_returns404_forAlreadyDeleted() throws Exception {
    doThrow(new ResourceNotFoundException("Expense", expenseId))
        .when(expenseService)
        .deleteExpense(expenseId, deviceId.toString());

    mockMvc
        .perform(
            delete("/api/v1/expenses/{id}", expenseId).header("X-Device-Id", deviceId.toString()))
        .andExpect(status().isNotFound());
  }
}
