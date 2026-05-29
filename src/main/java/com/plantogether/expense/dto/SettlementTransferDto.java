package com.plantogether.expense.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single minimal settlement transfer between two trip members.
 *
 * <p>{@code status} is {@code "PENDING"} for a freshly computed transfer and {@code "DONE"} once a
 * matching row has been persisted (story 5.4.2). {@code settledAt} / {@code settledByMemberId} are
 * populated only for DONE transfers.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementTransferDto {

  public static final String STATUS_PENDING = "PENDING";
  public static final String STATUS_DONE = "DONE";

  private UUID fromMemberId;
  private UUID toMemberId;
  private BigDecimal amount;
  private String currency;
  private String status;
  private Instant settledAt;
  private UUID settledByMemberId;
}
