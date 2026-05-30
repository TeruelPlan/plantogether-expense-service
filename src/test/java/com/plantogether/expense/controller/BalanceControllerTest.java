package com.plantogether.expense.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plantogether.common.exception.AccessDeniedException;
import com.plantogether.common.grpc.TripClient;
import com.plantogether.common.security.SecurityAutoConfiguration;
import com.plantogether.expense.dto.BalanceResponse;
import com.plantogether.expense.dto.SettlementTransferDto;
import com.plantogether.expense.dto.SettlementTransferResponse;
import com.plantogether.expense.exception.GlobalExceptionHandler;
import com.plantogether.expense.service.BalanceService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(BalanceController.class)
@Import({SecurityAutoConfiguration.class, GlobalExceptionHandler.class})
class BalanceControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private BalanceService balanceService;

  @MockitoBean private TripClient tripClient;

  private final UUID deviceId = UUID.randomUUID();
  private final UUID tripId = UUID.randomUUID();
  private final UUID from = UUID.randomUUID();
  private final UUID to = UUID.randomUUID();

  @AfterEach
  void tearDown() {
    Mockito.reset(balanceService, tripClient);
  }

  private BalanceResponse sample() {
    return BalanceResponse.builder()
        .tripId(tripId)
        .referenceCurrency("EUR")
        .participantBalances(Map.of(from, new BigDecimal("-50.00"), to, new BigDecimal("50.00")))
        .settlements(
            List.of(
                SettlementTransferDto.builder()
                    .fromMemberId(from)
                    .toMemberId(to)
                    .amount(new BigDecimal("50.00"))
                    .currency("EUR")
                    .status(SettlementTransferDto.STATUS_PENDING)
                    .build()))
        .allSettled(false)
        .computedAt(Instant.now())
        .build();
  }

  private String markDoneBody(String amount) {
    return """
    {
      "fromMemberId": "%s",
      "toMemberId": "%s",
      "amount": %s,
      "currency": "EUR"
    }
    """
        .formatted(from, to, amount);
  }

  // ---------------------------------------------------------------------------
  // GET /balance (5.4.1)
  // ---------------------------------------------------------------------------

  @Test
  void getBalance_returns200_withMember() throws Exception {
    when(balanceService.getBalance(eq(tripId), eq(deviceId.toString()))).thenReturn(sample());

    mockMvc
        .perform(
            get("/api/v1/trips/{tripId}/balance", tripId)
                .header("X-Device-Id", deviceId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tripId").value(tripId.toString()))
        .andExpect(jsonPath("$.referenceCurrency").value("EUR"))
        .andExpect(jsonPath("$.settlements").isArray())
        .andExpect(jsonPath("$.settlements.length()").value(1))
        .andExpect(jsonPath("$.settlements[0].amount").value(50.00))
        .andExpect(jsonPath("$.settlements[0].currency").value("EUR"))
        .andExpect(jsonPath("$.allSettled").value(false));
  }

  @Test
  void getBalance_returns403_withNonMember() throws Exception {
    when(balanceService.getBalance(eq(tripId), eq(deviceId.toString())))
        .thenThrow(new AccessDeniedException("Not a member"));

    mockMvc
        .perform(
            get("/api/v1/trips/{tripId}/balance", tripId)
                .header("X-Device-Id", deviceId.toString()))
        .andExpect(status().isForbidden());
  }

  @Test
  void getBalance_returns401_withoutDeviceHeader() throws Exception {
    mockMvc
        .perform(get("/api/v1/trips/{tripId}/balance", tripId))
        .andExpect(status().isUnauthorized());
  }

  // ---------------------------------------------------------------------------
  // PATCH /balance/settlements (5.4.2)
  // ---------------------------------------------------------------------------

  @Test
  void patchSettlements_returns200_withValidBody() throws Exception {
    SettlementTransferResponse saved =
        SettlementTransferResponse.builder()
            .id(UUID.randomUUID())
            .tripId(tripId)
            .fromMemberId(from)
            .toMemberId(to)
            .amount(new BigDecimal("50.00"))
            .currency("EUR")
            .settledAt(Instant.now())
            .settledByMemberId(from)
            .build();
    when(balanceService.markTransferDone(eq(tripId), eq(deviceId.toString()), any()))
        .thenReturn(saved);

    mockMvc
        .perform(
            patch("/api/v1/trips/{tripId}/balance/settlements", tripId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(markDoneBody("50.00")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fromMemberId").value(from.toString()))
        .andExpect(jsonPath("$.amount").value(50.00));
  }

  @Test
  void patchSettlements_returns400_withNegativeAmount() throws Exception {
    mockMvc
        .perform(
            patch("/api/v1/trips/{tripId}/balance/settlements", tripId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(markDoneBody("-5.00")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void patchSettlements_returns409_whenNotInPlan() throws Exception {
    when(balanceService.markTransferDone(eq(tripId), eq(deviceId.toString()), any()))
        .thenThrow(
            new ResponseStatusException(
                HttpStatus.CONFLICT, "Transfer does not match current settlement plan"));

    mockMvc
        .perform(
            patch("/api/v1/trips/{tripId}/balance/settlements", tripId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(markDoneBody("50.00")))
        .andExpect(status().isConflict());
  }

  @Test
  void patchSettlements_returns403_forUnrelatedDevice() throws Exception {
    when(balanceService.markTransferDone(eq(tripId), eq(deviceId.toString()), any()))
        .thenThrow(new AccessDeniedException("Not allowed"));

    mockMvc
        .perform(
            patch("/api/v1/trips/{tripId}/balance/settlements", tripId)
                .header("X-Device-Id", deviceId.toString())
                .contentType("application/json")
                .content(markDoneBody("50.00")))
        .andExpect(status().isForbidden());
  }
}
