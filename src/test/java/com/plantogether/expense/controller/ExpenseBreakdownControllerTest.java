package com.plantogether.expense.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plantogether.common.exception.AccessDeniedException;
import com.plantogether.common.grpc.TripClient;
import com.plantogether.common.security.SecurityAutoConfiguration;
import com.plantogether.expense.domain.ExpenseCategory;
import com.plantogether.expense.dto.BreakdownResponse;
import com.plantogether.expense.dto.CategoryBreakdownEntry;
import com.plantogether.expense.exception.GlobalExceptionHandler;
import com.plantogether.expense.service.ExpenseBreakdownService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ExpenseBreakdownController.class)
@Import({SecurityAutoConfiguration.class, GlobalExceptionHandler.class})
class ExpenseBreakdownControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ExpenseBreakdownService breakdownService;

  @MockitoBean private TripClient tripClient;

  private final UUID deviceId = UUID.randomUUID();
  private final UUID tripId = UUID.randomUUID();

  @AfterEach
  void tearDown() {
    Mockito.reset(breakdownService, tripClient);
  }

  private BreakdownResponse sample() {
    return BreakdownResponse.builder()
        .tripId(tripId)
        .referenceCurrency("EUR")
        .totalAmount(new BigDecimal("100.00"))
        .categories(
            List.of(
                CategoryBreakdownEntry.builder()
                    .category(ExpenseCategory.FOOD)
                    .totalAmount(new BigDecimal("100.00"))
                    .percentage(new BigDecimal("100.00"))
                    .expenseCount(2)
                    .build()))
        .computedAt(Instant.now())
        .build();
  }

  @Test
  void getBreakdown_returns200_withPayload() throws Exception {
    when(breakdownService.getBreakdown(eq(tripId), eq(deviceId.toString()))).thenReturn(sample());

    mockMvc
        .perform(
            get("/api/v1/trips/{tripId}/expenses/breakdown", tripId)
                .header("X-Device-Id", deviceId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.referenceCurrency").value("EUR"))
        .andExpect(jsonPath("$.totalAmount").value(100.00))
        .andExpect(jsonPath("$.categories.length()").value(1))
        .andExpect(jsonPath("$.categories[0].category").value("FOOD"))
        .andExpect(jsonPath("$.categories[0].percentage").value(100.00));
  }

  @Test
  void getBreakdown_returns403_forNonMember() throws Exception {
    when(breakdownService.getBreakdown(eq(tripId), eq(deviceId.toString())))
        .thenThrow(new AccessDeniedException("Not a member"));

    mockMvc
        .perform(
            get("/api/v1/trips/{tripId}/expenses/breakdown", tripId)
                .header("X-Device-Id", deviceId.toString()))
        .andExpect(status().isForbidden());
  }

  @Test
  void getBreakdown_returns200_withEmptyPayload() throws Exception {
    BreakdownResponse empty =
        BreakdownResponse.builder()
            .tripId(tripId)
            .referenceCurrency("EUR")
            .totalAmount(BigDecimal.ZERO)
            .categories(List.of())
            .computedAt(Instant.now())
            .build();
    when(breakdownService.getBreakdown(eq(tripId), eq(deviceId.toString()))).thenReturn(empty);

    mockMvc
        .perform(
            get("/api/v1/trips/{tripId}/expenses/breakdown", tripId)
                .header("X-Device-Id", deviceId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.categories.length()").value(0));
  }
}
