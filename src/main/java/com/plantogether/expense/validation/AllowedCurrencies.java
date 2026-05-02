package com.plantogether.expense.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Restricts a String field to the MVP-supported ISO 4217 currency codes. The single source of truth
 * lives in {@link AllowedCurrenciesValidator#SUPPORTED}.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = AllowedCurrenciesValidator.class)
public @interface AllowedCurrencies {
  String message() default "currency must be one of EUR, USD, GBP, CHF, JPY";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
