package com.plantogether.expense.dto;

import com.plantogether.expense.domain.ExpenseCategory;
import com.plantogether.expense.domain.SplitMode;
import com.plantogether.expense.validation.AllowedCurrencies;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordExpenseRequest {

    @NotNull
    @Positive
    @Digits(integer = 15, fraction = 4)
    private BigDecimal amount;

    @NotBlank
    @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be ISO 4217")
    @AllowedCurrencies
    private String currency;

    @NotNull
    private ExpenseCategory category;

    @NotBlank
    @Size(max = 255)
    private String description;

    @Size(max = 500)
    private String receiptKey;

    @NotNull
    private SplitMode splitMode;

    @Valid
    @Size(min = 1, message = "splits, if provided, must not be empty")
    private List<SplitInput> splits;

    private UUID paidBy;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SplitInput {

        @NotNull
        private UUID deviceId;

        @NotNull
        @Positive
        @Digits(integer = 15, fraction = 4)
        private BigDecimal shareAmount;
    }
}
