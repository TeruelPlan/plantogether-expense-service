package com.plantogether.expense.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "expense")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Expense {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id")
    private UUID id;

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Column(name = "paid_by", nullable = false)
    private UUID paidBy;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 50)
    private ExpenseCategory category;

    @Column(name = "description", nullable = false, length = 255)
    private String description;

    @Column(name = "receipt_key", length = 500)
    private String receiptKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "split_mode", nullable = false, length = 50)
    private SplitMode splitMode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "exchange_rate", nullable = false, precision = 19, scale = 6)
    private BigDecimal exchangeRate;

    @Column(name = "amount_in_reference_currency", nullable = false, precision = 19, scale = 4)
    private BigDecimal amountInReferenceCurrency;

    @Column(name = "reference_currency", nullable = false, length = 3)
    private String referenceCurrency;

    @Enumerated(EnumType.STRING)
    @Column(name = "rate_source", nullable = false, length = 16)
    private RateSource rateSource;

    @Column(name = "rate_fetched_at", nullable = false)
    private Instant rateFetchedAt;

    @OneToMany(
            mappedBy = "expense",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    @Builder.Default
    private List<ExpenseSplit> splits = new ArrayList<>();

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void addSplit(ExpenseSplit split) {
        split.setExpense(this);
        splits.add(split);
    }
}
