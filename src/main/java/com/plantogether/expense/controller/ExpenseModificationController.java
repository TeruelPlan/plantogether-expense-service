package com.plantogether.expense.controller;

import com.plantogether.expense.dto.ExpenseResponse;
import com.plantogether.expense.dto.UpdateExpenseRequest;
import com.plantogether.expense.service.ExpenseService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Flat endpoints for editing and soft-deleting an expense by id. Trip is resolved from the
 * persisted record — no {@code tripId} in the path. See {@code docs/api-contracts-expense.md}.
 */
@RestController
@RequestMapping("/api/v1/expenses")
@RequiredArgsConstructor
public class ExpenseModificationController {

  private final ExpenseService expenseService;

  @PutMapping("/{expenseId}")
  public ExpenseResponse update(
      Authentication auth,
      @PathVariable UUID expenseId,
      @Valid @RequestBody UpdateExpenseRequest req) {
    return expenseService.updateExpense(expenseId, auth.getName(), req);
  }

  @DeleteMapping("/{expenseId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(Authentication auth, @PathVariable UUID expenseId) {
    expenseService.deleteExpense(expenseId, auth.getName());
  }
}
