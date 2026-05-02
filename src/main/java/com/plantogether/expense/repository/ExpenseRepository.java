package com.plantogether.expense.repository;

import com.plantogether.expense.domain.Expense;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExpenseRepository extends JpaRepository<Expense, UUID> {

  @EntityGraph(attributePaths = {"splits"})
  Page<Expense> findByTripIdAndDeletedAtIsNull(UUID tripId, Pageable pageable);

  Optional<Expense> findByIdAndDeletedAtIsNull(UUID id);
}
