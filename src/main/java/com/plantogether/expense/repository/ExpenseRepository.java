package com.plantogether.expense.repository;

import com.plantogether.expense.domain.Expense;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExpenseRepository extends JpaRepository<Expense, UUID> {

  @EntityGraph(attributePaths = {"splits"})
  Page<Expense> findByTripIdAndDeletedAtIsNull(UUID tripId, Pageable pageable);

  /** All non-deleted expenses for a trip with their splits eagerly fetched (settlement compute). */
  @EntityGraph(attributePaths = {"splits"})
  List<Expense> findAllByTripIdAndDeletedAtIsNull(UUID tripId);

  Optional<Expense> findByIdAndDeletedAtIsNull(UUID id);
}
