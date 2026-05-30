package com.plantogether.expense.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Trip-wide settlement snapshot returned by {@code GET /api/v1/trips/{tripId}/balance}.
 *
 * <p>The balance is identical for every member; per-member "my turn" highlighting happens client
 * side. Identity is the per-trip {@code memberId} (UUID). Jackson serializes UUID map keys to
 * strings.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BalanceResponse {

  private UUID tripId;
  private String referenceCurrency;

  /** Net balance per member: positive = creditor (owed), negative = debtor (owes). */
  private Map<UUID, BigDecimal> participantBalances;

  /** Minimal transfer list produced by the greedy algorithm. */
  private List<SettlementTransferDto> settlements;

  /** True when every computed transfer is DONE, or there were no transfers to begin with. */
  private boolean allSettled;

  /** When the snapshot was computed (for client staleness display). */
  private Instant computedAt;
}
