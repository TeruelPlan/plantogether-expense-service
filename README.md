# Expense Service

> Service de gestion du budget partagé et des dépenses de voyage

## Rôle dans l'architecture

L'Expense Service gère les dépenses du groupe, la répartition des frais et l'algorithme d'équilibrage des comptes.
Il vérifie l'appartenance au trip via gRPC (TripService.CheckMembership) et récupère la devise de référence via
TripService.GetTripCurrency pour la gestion multi-devises.

## Fonctionnalités

- Enregistrement des dépenses avec modes de répartition (EQUAL / CUSTOM / PERCENTAGE)
- Soft delete des dépenses (`deleted_at`)
- Algorithme d'équilibrage greedy (minimum de transactions pour N participants)
- Gestion multi-devises (conversion via API ECB/Fixer dans la devise du trip)
- Export CSV ou PDF du bilan de dépenses
- Vérification d'appartenance via gRPC avant chaque opération

## Endpoints REST

| Méthode | Endpoint | Description |
|---------|----------|-------------|
| POST | `/api/v1/trips/{id}/expenses` | Ajouter une dépense |
| GET | `/api/v1/trips/{id}/expenses` | Liste des dépenses (paginé) |
| PUT | `/api/v1/expenses/{id}` | Modifier une dépense |
| DELETE | `/api/v1/expenses/{id}` | Supprimer (soft delete) |
| GET | `/api/v1/trips/{id}/balance` | Équilibrage calculé |
| GET | `/api/v1/trips/{id}/expenses/export` | Export CSV ou PDF |

## gRPC Clients

- `TripService.CheckMembership(tripId, userId)` — vérification d'appartenance
- `TripService.GetTripCurrency(tripId)` — devise de référence pour l'équilibrage
- `FileService.GetPresignedUrl(key)` — URL de lecture pour les justificatifs

## Modèle de données (`db_expense`)

**expense**

| Colonne | Type | Description |
|---------|------|-------------|
| `id` | UUID PK | Identifiant unique (UUID v7) |
| `trip_id` | UUID NOT NULL | Référence au trip |
| `paid_by` | UUID NOT NULL | keycloak_id du payeur |
| `amount` | DECIMAL NOT NULL | Montant |
| `currency` | VARCHAR(3) NOT NULL | Devise (ISO 4217) |
| `category` | ENUM NOT NULL | FOOD / TRANSPORT / ACCOMMODATION / ACTIVITY / OTHER |
| `description` | VARCHAR(255) NOT NULL | Description |
| `receipt_key` | VARCHAR(500) NULLABLE | Clé MinIO du justificatif |
| `split_mode` | ENUM NOT NULL | EQUAL / CUSTOM / PERCENTAGE |
| `created_at` | TIMESTAMP NOT NULL | |
| `updated_at` | TIMESTAMP NOT NULL | |
| `deleted_at` | TIMESTAMP NULLABLE | Soft delete |

**expense_split**

| Colonne | Type | Description |
|---------|------|-------------|
| `id` | UUID PK | |
| `expense_id` | UUID NOT NULL FK→expense | |
| `keycloak_id` | UUID NOT NULL | Participant concerné |
| `share_amount` | DECIMAL NOT NULL | Part à la charge de cet utilisateur |

## Algorithme d'équilibrage

L'algorithme `BalanceCalculator` minimise le nombre de transactions pour équilibrer les comptes du groupe :

1. Calculer le solde net de chaque participant : `total_payé - total_dû`
2. Séparer les débiteurs (solde < 0) et les créditeurs (solde > 0)
3. Trier les deux listes par montant absolu décroissant
4. Pour chaque paire (plus gros débiteur, plus gros créditeur) : transférer le minimum des deux montants
5. Répéter jusqu'à ce que tous les soldes soient à zéro (± 0.01)

Garantit au maximum **N-1 transactions** pour N participants. Le multi-devises est géré en convertissant toutes
les dépenses dans la devise du trip via l'API ECB/Fixer.

## Événements RabbitMQ (Exchange : `plantogether.events`)

**Publie :**

| Routing Key | Déclencheur |
|-------------|-------------|
| `expense.created` | Ajout d'une dépense |
| `expense.deleted` | Suppression d'une dépense |

**Consomme :** aucun

## Configuration

```yaml
server:
  port: 8084

spring:
  application:
    name: plantogether-expense-service
  datasource:
    url: jdbc:postgresql://postgres:5432/db_expense
    username: ${DB_USER}
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate

grpc:
  client:
    trip-service:
      address: static://trip-service:9081
    file-service:
      address: static://file-service:9088
  server:
    port: 9084
```

## Lancer en local

```bash
# Prérequis : docker compose --profile essential up -d
# + plantogether-proto et plantogether-common installés

mvn spring-boot:run
```

## Dépendances

- **Keycloak 24+** : validation JWT
- **PostgreSQL 16** (`db_expense`) : dépenses et répartitions
- **RabbitMQ** : publication d'événements (`expense.created`, `expense.deleted`)
- **Redis** : rate limiting (Bucket4j — 30 dépenses/heure/user)
- **Trip Service** (gRPC 9081) : vérification appartenance + devise du trip
- **File Service** (gRPC 9088) : presigned URLs pour les justificatifs
- **plantogether-proto** : contrats gRPC (client + serveur)
- **plantogether-common** : DTOs events, CorsConfig

## Sécurité

- Tous les endpoints requièrent un token Bearer Keycloak valide
- L'appartenance au trip est vérifiée via gRPC avant chaque opération
- Seul le créateur d'une dépense ou l'ORGANIZER peut la modifier ou la supprimer
- Zero PII stockée (uniquement des `keycloak_id`)
