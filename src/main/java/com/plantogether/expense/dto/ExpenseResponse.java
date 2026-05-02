package com.plantogether.expense.dto;

import com.plantogether.expense.domain.Expense;
import com.plantogether.expense.domain.ExpenseCategory;
import com.plantogether.expense.domain.RateSource;
import com.plantogether.expense.domain.SplitMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpenseResponse {

    private UUID id;
    private UUID tripId;
    private UUID paidByDeviceId;
    private BigDecimal amount;
    private String currency;
    private ExpenseCategory category;
    private String description;
    private String receiptKey;
    private SplitMode splitMode;
    private List<SplitOutput> splits;
    private Instant createdAt;
    private Instant updatedAt;
    private BigDecimal exchangeRate;
    private BigDecimal amountInReferenceCurrency;
    private String referenceCurrency;
    private RateSource rateSource;
    private Instant rateFetchedAt;

    public static ExpenseResponse from(Expense entity) {
        List<SplitOutput> splits =
                entity.getSplits().stream()
                        .sorted(Comparator.comparing(s -> s.getDeviceId().toString()))
                        .map(s -> new SplitOutput(s.getDeviceId(), s.getShareAmount()))
                        .toList();

        return ExpenseResponse.builder()
                .id(entity.getId())
                .tripId(entity.getTripId())
                .paidByDeviceId(entity.getPaidBy())
                .amount(entity.getAmount())
                .currency(entity.getCurrency())
                .category(entity.getCategory())
                .description(entity.getDescription())
                .receiptKey(entity.getReceiptKey())
                .splitMode(entity.getSplitMode())
                .splits(splits)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .exchangeRate(entity.getExchangeRate())
                .amountInReferenceCurrency(entity.getAmountInReferenceCurrency())
                .referenceCurrency(entity.getReferenceCurrency())
                .rateSource(entity.getRateSource())
                .rateFetchedAt(entity.getRateFetchedAt())
                .build();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SplitOutput {
        private UUID deviceId;
        private BigDecimal shareAmount;
    }
}
