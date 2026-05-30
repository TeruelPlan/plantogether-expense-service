package com.plantogether.expense.repository;

import com.plantogether.expense.domain.SettlementTransfer;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementTransferRepository extends JpaRepository<SettlementTransfer, UUID> {

  /** All persisted (DONE) transfers for a trip — used to flag computed transfers as DONE. */
  List<SettlementTransfer> findByTripId(UUID tripId);

  /** Backs the idempotent re-tap path (AC 4): an already-marked transfer returns the same row. */
  Optional<SettlementTransfer> findByTripIdAndFromMemberIdAndToMemberIdAndAmountAndCurrency(
      UUID tripId, UUID fromMemberId, UUID toMemberId, BigDecimal amount, String currency);
}
