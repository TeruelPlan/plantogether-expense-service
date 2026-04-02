# CLAUDE.md

This file provides guidance to Claude when working with code in this repository.

## Commands

```bash
# Build
mvn clean package -DskipTests

# Build with tests
mvn clean package

# Run locally
mvn spring-boot:run

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=MyTestClass

# Run a single test method
mvn test -Dtest=MyTestClass#myMethod
```

**Prerequisites:** install shared libs first:
```bash
cd ../plantogether-proto && mvn clean install
cd ../plantogether-common && mvn clean install
```

## Architecture

Spring Boot 3.3.6 microservice (Java 25). Manages trip expenses, cost splitting, and settlement calculations.

**Ports:** REST `8084` · gRPC `9084` (server — reserved for future consumers)

**Package:** `com.plantogether.expense`

### Package structure

```
com.plantogether.expense/
├── config/          # SecurityConfig, RabbitConfig
├── controller/      # REST controllers
├── domain/          # JPA entities (Expense, ExpenseSplit)
├── repository/      # Spring Data JPA
├── service/         # Business logic + BalanceCalculator
├── dto/             # Request/Response DTOs (Lombok @Data @Builder)
├── grpc/
│   └── client/      # TripGrpcClient (CheckMembership + GetTripCurrency)
└── event/
    └── publisher/   # RabbitMQ publishers (ExpenseCreated, ExpenseDeleted)
```

### Infrastructure dependencies

| Dependency | Default (local) | Purpose |
|---|---|---|
| PostgreSQL 16 | `localhost:5432/plantogether_expense` | Primary persistence (db_expense) |
| RabbitMQ | `localhost:5672` | Event publishing |
| Redis | `localhost:6379` | Caching exchange rates, balance calculations |
| Keycloak 24+ | `localhost:8180` realm `plantogether` | JWT validation via JWKS |
| trip-service gRPC | `localhost:9081` | CheckMembership + GetTripCurrency |
| MinIO | `localhost:9000` | Receipt file storage (presigned URLs) |


### Domain model (db_expense)

**`expense`** — id (UUID), trip_id (UUID), paid_by (Keycloak UUID), amount (DECIMAL), currency (VARCHAR),
category (ENUM: `TRANSPORT`/`ACCOMMODATION`/`FOOD`/`ACTIVITY`/`OTHER`), description, receipt_key (MinIO key),
split_mode (ENUM: `EQUAL`/`CUSTOM`/`PERCENTAGE`), created_at, updated_at, deleted_at (soft delete).

**`expense_split`** — id (UUID), expense_id (FK), keycloak_id, share_amount (DECIMAL).
One row per participant in the expense.

### gRPC clients

- `TripGrpcService.CheckMembership(tripId, userId)` — called before every write (trip-service:9081)
- `TripGrpcService.GetTripCurrency(tripId)` — called to get reference currency for balance calculation

### REST API (`/api/v1/`)

| Method | Endpoint | Auth | Notes |
|---|---|---|---|
| POST | `/api/v1/trips/{tripId}/expenses` | Bearer JWT + member | Add expense |
| GET | `/api/v1/trips/{tripId}/expenses` | Bearer JWT + member | List (paginated, `?page=0&size=20&sort=createdAt,desc`) |
| PUT | `/api/v1/expenses/{id}` | Bearer JWT + payer or ORGANIZER | Modify expense |
| DELETE | `/api/v1/expenses/{id}` | Bearer JWT + payer or ORGANIZER | Soft delete |
| GET | `/api/v1/trips/{tripId}/balance` | Bearer JWT + member | Computed balance + settlements |
| GET | `/api/v1/trips/{tripId}/expenses/export` | Bearer JWT + member | Export CSV or PDF |

### Settlement algorithm (BalanceCalculator)

Greedy debt minimization — produces at most N-1 transactions for N participants:
1. Compute net balance per participant: `total_paid - total_owed`
2. Separate debtors (balance < 0) from creditors (balance > 0)
3. Sort both lists by absolute value descending
4. For each pair (largest debtor, largest creditor): transfer `min(|debtor|, |creditor|)`
5. Repeat until all balances = 0 (±0.01)

Multi-currency: all amounts converted to trip's reference currency via ECB/Fixer API before calculation.

### RabbitMQ events

**Publishes** (exchange `plantogether.events`):
- `expense.created` — routing key `expense.created` — on expense creation
- `expense.deleted` — routing key `expense.deleted` — on soft delete

This service does **not** consume any events.

### Security

- Stateless JWT via `KeycloakJwtConverter` — `realm_access.roles` → `ROLE_<ROLE>` Spring authorities
- Principal name = Keycloak subject UUID
- Public endpoints: `/actuator/health`, `/actuator/info`
- Zero PII stored — only Keycloak UUIDs (`paid_by`, `keycloak_id` in splits)

### Environment variables

| Variable | Default |
|---|---|
| `DB_HOST` | `localhost` |
| `DB_USER` | `plantogether` |
| `DB_PASSWORD` | `plantogether` |
| `RABBITMQ_HOST` | `localhost` |
| `RABBITMQ_USER` | `guest` |
| `RABBITMQ_PASSWORD` | `guest` |
| `REDIS_HOST` | `localhost` |
| `KEYCLOAK_URL` | `http://localhost:8180` |
| `MINIO_ENDPOINT` | — |
| `MINIO_ACCESS_KEY` | — |
| `MINIO_SECRET_KEY` | — |
| `TRIP_SERVICE_GRPC_HOST` | `localhost` |
| `TRIP_SERVICE_GRPC_PORT` | `9081` |

