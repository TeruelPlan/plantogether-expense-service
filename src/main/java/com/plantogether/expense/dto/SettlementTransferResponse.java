package com.plantogether.expense.dto;

import com.plantogether.expense.domain.SettlementTransfer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Response body for a persisted (DONE) settlement transfer (story 5.4.2). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementTransferResponse {

  private UUID id;
  private UUID tripId;
  private UUID fromMemberId;
  private UUID toMemberId;
  private BigDecimal amount;
  private String currency;
  private Instant settledAt;
  private UUID settledByMemberId;

  public static SettlementTransferResponse from(SettlementTransfer entity) {
    return SettlementTransferResponse.builder()
        .id(entity.getId())
        .tripId(entity.getTripId())
        .fromMemberId(entity.getFromMemberId())
        .toMemberId(entity.getToMemberId())
        .amount(entity.getAmount())
        .currency(entity.getCurrency())
        .settledAt(entity.getSettledAt())
        .settledByMemberId(entity.getSettledByMemberId())
        .build();
  }
}
