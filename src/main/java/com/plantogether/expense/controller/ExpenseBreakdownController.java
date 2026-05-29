package com.plantogether.expense.controller;

import com.plantogether.expense.dto.BreakdownResponse;
import com.plantogether.expense.service.ExpenseBreakdownService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only per-category spend breakdown (story 5.5).
 *
 * <p>Security is wired by {@code SecurityAutoConfiguration} from {@code plantogether-common}; the
 * caller device id is {@code auth.getName()}. No {@code @Transactional} on controllers.
 */
@RestController
@RequestMapping("/api/v1/trips/{tripId}/expenses/breakdown")
@RequiredArgsConstructor
public class ExpenseBreakdownController {

  private final ExpenseBreakdownService breakdownService;

  @GetMapping
  public BreakdownResponse getBreakdown(Authentication auth, @PathVariable UUID tripId) {
    return breakdownService.getBreakdown(tripId, auth.getName());
  }
}
