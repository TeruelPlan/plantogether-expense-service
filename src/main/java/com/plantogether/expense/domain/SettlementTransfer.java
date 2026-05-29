package com.plantogether.expense.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/**
 * A settlement transfer that a member has marked as done (story 5.4.2).
 *
 * <p>Identity is the per-trip {@code memberId}. A row exists only after the transfer has actually
 * been settled; the computed-but-pending transfers are never persisted.
 */
@Entity
@Table(name = "settlement_transfer")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SettlementTransfer {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.TIME)
  @Column(name = "id")
  private UUID id;

  @Column(name = "trip_id", nullable = false)
  private UUID tripId;

  @Column(name = "from_member_id", nullable = false)
  private UUID fromMemberId;

  @Column(name = "to_member_id", nullable = false)
  private UUID toMemberId;

  @Column(name = "amount", nullable = false, precision = 19, scale = 4)
  private BigDecimal amount;

  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  @Column(name = "settled_at", nullable = false)
  private Instant settledAt;

  @Column(name = "settled_by_member_id", nullable = false)
  private UUID settledByMemberId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  void onPersist() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    if (settledAt == null) settledAt = now;
  }
}
