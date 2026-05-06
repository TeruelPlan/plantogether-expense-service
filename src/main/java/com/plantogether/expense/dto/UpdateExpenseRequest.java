package com.plantogether.expense.dto;

import com.plantogether.expense.domain.ExpenseCategory;
import com.plantogether.expense.domain.SplitMode;
import com.plantogether.expense.validation.AllowedCurrencies;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Full-replacement edit payload for an expense (PUT semantics — not a partial PATCH). Reuses {@link
 * RecordExpenseRequest.SplitInput} for split entries. Payer (paidBy) is intentionally absent —
 * ownership of an expense is immutable.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateExpenseRequest {

  @NotNull
  @Positive
  @Digits(integer = 15, fraction = 4)
  private BigDecimal amount;

  @NotBlank
  @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be ISO 4217")
  @AllowedCurrencies
  private String currency;

  @NotNull private ExpenseCategory category;

  @NotBlank
  @Size(max = 255)
  private String description;

  @Size(max = 500)
  @Pattern(
      regexp = "^trips/[0-9a-fA-F-]{36}/EXPENSE_RECEIPT/[0-9a-fA-F-]{36}.*$",
      message = "receiptKey must match the trip receipt prefix")
  private String receiptKey;

  @NotNull private SplitMode splitMode;

  @NotNull
  @NotEmpty
  @Valid
  @Size(min = 1, message = "splits must not be empty")
  private List<RecordExpenseRequest.SplitInput> splits;

  /**
   * Cross-field invariant: for CUSTOM splits, the sum of share amounts must equal {@link #amount}
   * within ±0.01. PERCENTAGE and EQUAL bypass this check (server expands them). Uses {@code
   * BigDecimal#compareTo}, never {@code equals}.
   */
  @AssertTrue(message = "sum of CUSTOM splits must equal amount within ±0.01")
  public boolean isSplitsSumValid() {
    if (splitMode != SplitMode.CUSTOM || splits == null || splits.isEmpty() || amount == null) {
      return true;
    }
    BigDecimal sum =
        splits.stream()
            .map(RecordExpenseRequest.SplitInput::getShareAmount)
            .filter(java.util.Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    return sum.subtract(amount).abs().compareTo(new BigDecimal("0.01")) <= 0;
  }
}
