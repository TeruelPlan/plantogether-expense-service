package com.plantogether.expense.controller;

import com.plantogether.expense.dto.BalanceResponse;
import com.plantogether.expense.dto.MarkTransferDoneRequest;
import com.plantogether.expense.dto.SettlementTransferResponse;
import com.plantogether.expense.service.BalanceService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trip settlement endpoints.
 *
 * <p>Security is wired by {@code SecurityAutoConfiguration} from {@code plantogether-common}; the
 * caller device id is {@code auth.getName()} (set by {@code DeviceIdFilter}). No
 * {@code @Transactional} on controllers (TEAM_CONVENTIONS).
 */
@RestController
@RequestMapping("/api/v1/trips/{tripId}/balance")
@RequiredArgsConstructor
public class BalanceController {

  private final BalanceService balanceService;

  /** Story 5.4.1 — read-only settlement plan. */
  @GetMapping
  public BalanceResponse getBalance(Authentication auth, @PathVariable UUID tripId) {
    return balanceService.getBalance(tripId, auth.getName());
  }

  /** Story 5.4.2 — mark a settlement transfer as done. */
  @PatchMapping("/settlements")
  public SettlementTransferResponse markDone(
      Authentication auth,
      @PathVariable UUID tripId,
      @Valid @RequestBody MarkTransferDoneRequest req) {
    return balanceService.markTransferDone(tripId, auth.getName(), req);
  }
}
