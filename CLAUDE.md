# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
mvn package -DskipTests

# Run locally
mvn spring-boot:run

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=MyTestClass

# Run a single test method
mvn test -Dtest=MyTestClass#myMethod
```

## Architecture

This is a **Spring Boot 3.3.6 microservice** (Java 21) within the PlanTogether platform — a travel expense management service running on port **8084**.

### Key layers

- **Security** (`security/`): Stateless JWT resource server. `KeycloakJwtConverter` extracts Keycloak realm roles from the `realm_access.roles` JWT claim and maps them to `ROLE_<ROLE>` Spring authorities. The principal name is the Keycloak subject UUID.
- **Exception handling** (`exception/GlobalExceptionHandler`): Uses `ErrorResponse` and exceptions (`ResourceNotFoundException`, `AccessDeniedException`) from the shared `plantogether-common` library.
- **Database** (`db/migration/`): Flyway manages schema migrations. `ddl-auto=validate` — Hibernate validates against the DB but never modifies it. All schema changes must go through a new `V{n}__description.sql` migration file.

### Infrastructure dependencies

| Dependency | Default (local) | Purpose |
|---|---|---|
| PostgreSQL | `localhost:5432/plantogether_expense` | Primary persistence |
| RabbitMQ | `localhost:5672` (guest/guest) | Event publishing |
| Redis | `localhost:6379` | Caching (exchange rates, balance calculations) |
| Keycloak | `localhost:8180` realm `plantogether` | JWT validation via JWKS |
| MinIO | `localhost:9000` | Receipt file storage |
| Eureka | `localhost:8761` | Service discovery |

### Event publishing (RabbitMQ)

The service only **publishes** events; it does not consume any. Events: `ExpenseCreated`, `ExpenseUpdated`, `ExpenseDeleted`, `BalanceCalculated`, `KittyUpdated`.

### Settlement algorithm

Greedy debt minimization: compute net balance per participant, pair the largest debtor with the largest creditor iteratively. Produces at most N-1 transactions for N participants.

### Common library

`com.plantogether:plantogether-common:1.0.0-SNAPSHOT` must be available in the local Maven repository. It provides `ResourceNotFoundException`, `AccessDeniedException`, and `ErrorResponse`.
