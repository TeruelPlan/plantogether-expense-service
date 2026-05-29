package com.plantogether.expense.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Trip spend grouped by category, returned by {@code GET /api/v1/trips/{tripId}/expenses/breakdown}
 * (story 5.5). All amounts are in the trip reference currency; {@code categories} is ordered by
 * {@code totalAmount} descending and excludes zero-total categories.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BreakdownResponse {

  private UUID tripId;
  private String referenceCurrency;
  private BigDecimal totalAmount;
  private List<CategoryBreakdownEntry> categories;
  private Instant computedAt;
}
