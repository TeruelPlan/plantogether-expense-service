package com.plantogether.expense.controller;

import com.plantogether.expense.dto.ExpenseResponse;
import com.plantogether.expense.dto.RecordExpenseRequest;
import com.plantogether.expense.service.ExpenseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/trips/{tripId}/expenses")
@RequiredArgsConstructor
public class ExpenseController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Sort FIXED_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final ExpenseService expenseService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseResponse record(
            Authentication auth,
            @PathVariable UUID tripId,
            @Valid @RequestBody RecordExpenseRequest req) {
        return expenseService.recordExpense(tripId, auth.getName(), req);
    }

    @GetMapping
    public Page<ExpenseResponse> list(
            Authentication auth,
            @PathVariable UUID tripId,
            @PageableDefault(size = 20) Pageable pageable) {
        Pageable safePageable =
                PageRequest.of(
                        pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE), FIXED_SORT);
        return expenseService.listExpenses(tripId, auth.getName(), safePageable);
    }
}
