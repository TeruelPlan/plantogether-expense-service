package com.plantogether.expense.dto;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single minimal settlement transfer between two trip members.
 *
 * <p>Kept intentionally lean for story 5.4.1 (read-only). The {@code status} / {@code settledAt} /
 * {@code settledByMemberId} fields are introduced in story 5.4.2 once persistence lands.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementTransferDto {

  private UUID fromMemberId;
  private UUID toMemberId;
  private BigDecimal amount;
  private String currency;
}
