# Northstar Demo Bank

Northstar Demo Bank is a local Spring Boot microservice banking sandbox. It demonstrates
identity and session management, account and ledger operations, payments, Kafka events,
notifications, role-based authorization, and a browser dashboard.

The project runs in WSL Ubuntu with rootless Podman. The portable deployment packages
PostgreSQL with the application and initializes the four service databases on a named
volume.


## Project roadmap and where to resume

Open the [visual project roadmap](docs/roadmap.html) in a browser for the project history,
ordered milestones, completion criteria and session handoff. It is a dated project record,
not a live health monitor. Last reviewed: **26 October 2026**.

**Current state:** the four bank databases have been migrated into the portable
`northstar_bank_postgres` container and the application is being verified against that
database. The original `de_warehouse` database and its backups are preserved until the
verification and cleanup steps are complete.

| Order | Milestone | Status / completion evidence |
| --- | --- | --- |
| Foundation | Five services, UI, schemas, payments and Kafka outbox | Source present; current end-to-end verification pending |
| Feature expansion | Registration, seeded users, roles, theme, pre-seeded activation | Recorded history and source; regression verification pending |
| WSL deployment | Java 21 Containerfiles, Podman Compose, portable PostgreSQL | Portable database container and migration path implemented |
| 1 | Recover the database baseline | Four databases migrated from `de_warehouse`; source backups preserved |
| 2 — Current | Verify portable banking flows | Health, login, account, ledger, payment and notification checks |
| 3 | Stable, low-resource startup | Preflight and health checks; no restart loop for 15 minutes; measured resource budget |
| 4 | Maintainable baseline | Git history, regression tests, restore drill and session handoffs |
| 5 — Later | Pods and additional features | Only after stable baseline; persistence, probes and resource limits verified |

History is reconstructed from source, artifacts and prior project discussion because this
WSL directory has no Git repository. Exact change dates and authorship are not recoverable.
See the visual timeline for the Windows origin, feature additions, WSL migration,
containerization and the 26 October diagnosis.

After every work session, update the roadmap and this summary with the date, changed files,
verification results, remaining blocker and one concrete next action. Never record `.env`
values. The [pre-roadmap README](docs/README.before-roadmap-2026-10-26.md) is preserved verbatim.

## Current architecture

```text
Browser
   |
   | http://127.0.0.1:8080
   v
gateway-service
   |
   +--> identity-service       :8081
   +--> ledger-service         :8082
   +--> payment-service        :8083
   +--> notification-service   :8084
   |
   +--> kafka                  :9092

Portable PostgreSQL container:
northstar_bank_postgres
   |
   +--> bank_identity
   +--> bank_ledger
   +--> bank_payment
   +--> bank_notification
```

Only `gateway-service` publishes an application port. The other Java services, Kafka and
PostgreSQL are reachable only inside the Podman network. The Java services connect to the
portable database using the Compose service name `postgres`.

The migration process creates a new backup of each bank database before restoring it into
the portable container. It does not modify or delete the original backups.

## Project structure

```text
.
├── common/                    # Shared Java client and internal authentication code
├── gateway-service/           # Public web UI and API gateway
├── identity-service/          # Users, login, sessions, and roles
├── ledger-service/            # Accounts, balances, transfers, entries, and outbox
├── payment-service/           # Payments, idempotency, and ledger calls
├── notification-service/      # Kafka consumer and notification persistence
├── docs/                      # Visual roadmap and preserved README
├── runtime/                   # Local artifacts including migration backups (ignored by Git)
├── compose.yaml               # Podman Compose application stack
├── .env.example               # Environment template; copy to .env
├── pom.xml                    # Maven multi-module build
├── README.md
└── .gitignore
```

Each Java service has a multi-stage `Containerfile`. The builder stage uses Maven and
Java 21; the runtime stage contains only the service JAR and Java 21 JRE. POM files are
copied before source files so Maven dependencies can be reused from the image cache when
only application source changes.

## Runtime stack

| Component | Container or service | Port | Exposure |
| --- | --- | ---: | --- |
| Gateway | `northstar-bank_gateway-service_1` | 8080 | Host loopback |
| Identity | `northstar-bank_identity-service_1` | 8081 | Internal only |
| Ledger | `northstar-bank_ledger-service_1` | 8082 | Internal only |
| Payment | `northstar-bank_payment-service_1` | 8083 | Internal only |
| Notification | `northstar-bank_notification-service_1` | 8084 | Internal only |
| Kafka | `northstar-bank_kafka_1` | 9092 | Internal only |
| PostgreSQL | `northstar_bank_postgres` | 5432 | Internal only |

The Java services use one existing PostgreSQL login configured by `BANK_DB_USER` and
`BANK_DB_PASSWORD`. Database ownership remains logically separated by database name:

| Service | Database |
| --- | --- |
| Identity | `bank_identity` |
| Ledger | `bank_ledger` |
| Payment | `bank_payment` |
| Notification | `bank_notification` |

## Prerequisites

- WSL2 with Ubuntu
- Podman and `podman-compose`
- Podman and `podman-compose`
- The four source databases in the existing `de_warehouse` container for migration
- Internet access when pulling base images or rebuilding images

Check the required tools:

```bash
podman --version
podman-compose --version
podman ps -a
```

The existing `de_warehouse` container is used only as the migration source. Do not
connect it to the application network or delete it before migration verification.

## Deployment modes

The repository supports two deployment modes:

1. **Portable package (recommended for a new machine):** Compose starts PostgreSQL,
   creates the four service databases on the first empty database volume, and the
   services run their Flyway migrations. This mode does not depend on `de_warehouse`.
2. **Existing database mode:** the original `de_warehouse` container is used through
   `host.containers.internal:5432`. This mode is for the current WSL installation and
   must already contain the four service databases.

The portable PostgreSQL initialization runs only when its named volume is empty.
Starting the stack again does not recreate or drop databases. Do not remove the
`northstar-postgres-data` volume (or its Compose-project-prefixed name) unless you
intentionally want to destroy the portable demo data.

## First-time setup: portable package

Create a portable environment file:

```bash
cp .env.portable.example .env.portable
```

Set a local database password and a long random internal secret in `.env.portable`.
The portable stack uses the same database login for all four logical databases.

Create the shared network if needed and start the complete package:

```bash
podman network exists demobank-net || podman network create demobank-net
podman compose --env-file .env.portable \
  -f compose.yaml -f compose.portable.yaml up --build -d
```

The PostgreSQL image runs `deploy/postgres/init/00-create-databases.sql` on first
initialization. Each Java service then applies its own Flyway migrations to its
database:

```text
bank_identity       <- identity-service migrations
bank_ledger         <- ledger-service migrations
bank_payment        <- payment-service migrations
bank_notification   <- notification-service migrations
```

This is the recommended packaging path because the application, PostgreSQL image,
database creation, and schema migrations travel together.

Stop the portable package without deleting its data:

```bash
podman compose --env-file .env.portable \
  -f compose.yaml -f compose.portable.yaml stop
```

## Migrate from the existing database

Run these commands from:

```bash
cd /home/srieubuntu/projects/demobank
```

Create the application network if it does not exist:

```bash
podman network exists demobank-net || podman network create demobank-net
```

Create the portable environment file:

```bash
test -f .env.portable || cp .env.portable.example .env.portable
```

Set local credentials in `.env.portable`. Do not commit this file:

```env
BANK_DB_USER=de_admin
BANK_DB_PASSWORD=your_existing_database_password
BANK_INTERNAL_SECRET=your_long_random_internal_secret
PODMAN_NETWORK=demobank-net
GATEWAY_BIND_ADDRESS=127.0.0.1
GATEWAY_PORT=8080
```

Generate an internal secret if needed:

```bash
openssl rand -hex 48
```

Keep `.env.portable` local. It is excluded by `.gitignore` and must not be committed.

## Start the demo

The portable PostgreSQL container is the application database. The source `de_warehouse`
container is needed only during migration and is not used by the running services.

```bash
podman start de_warehouse
```

```bash
podman compose --env-file .env.portable \
  -f compose.yaml -f compose.portable.yaml up --build -d
```

Check the containers:

```bash
podman ps
```

Check the gateway health endpoint:

```bash
curl http://127.0.0.1:8080/actuator/health
```

Expected response:

```json
{"status":"UP"}
```

Open the dashboard:

```text
http://127.0.0.1:8080
```

If local-network access is intentionally required, change this value in `.env` before
starting:

```env
GATEWAY_BIND_ADDRESS=0.0.0.0
```

Use a firewall rule and trusted network when exposing the gateway beyond loopback.

## Demo accounts

The identity service seeds deterministic demo users idempotently:

| User | Email | Password |
| --- | --- | --- |
| Maya Thompson | `maya@northstar.demo` | `BankDemo!2026` |
| Alex Morgan | `alex@northstar.demo` | `BankDemo!2026` |
| Jordan Lee (admin) | `admin@northstar.demo` | `BankDemo!2026` |

The gateway dashboard defaults to the Maya customer profile.

## Demo workflow

1. Open the dashboard and sign in as Maya.
2. Review accounts, balances, transactions, beneficiaries, and cards.
3. Create a beneficiary for Alex.
4. Submit a transfer or payment.
5. The payment service calls the ledger service.
6. The ledger updates balances and writes an outbox event.
7. Kafka carries the `bank.transactions.v1` event.
8. The notification service consumes the event and stores the notification.
9. Review the resulting activity in the dashboard.
10. Sign in as Jordan to view the administrator operations screen and service health.

Customer registration is exposed through `POST /api/users`. Activation of pre-seeded customers is
available through `POST /api/v1/auth/signup`; public registration cannot create an admin.

## Gateway endpoints

The browser uses the gateway rather than calling internal services directly:

```text
POST   /api/login
POST   /api/logout
POST   /api/users
POST   /api/v1/auth/signup
GET    /api/me
GET    /api/accounts
GET    /api/transactions
GET    /api/beneficiaries
POST   /api/payments
GET    /api/notifications
GET    /api/events
GET    /api/operations       # Admin only
GET    /api/health
```

Internal service calls include the shared `X-Internal-Key` value and are not published
to the host.

## Logs and operations

Follow gateway logs:

```bash
podman compose --env-file .env.portable \
  -f compose.yaml -f compose.portable.yaml logs -f gateway-service
```

Follow all application logs:

```bash
podman compose --env-file .env.portable \
  -f compose.yaml -f compose.portable.yaml logs -f
```

Restart the application containers:

```bash
podman compose --env-file .env.portable \
  -f compose.yaml -f compose.portable.yaml restart
```

Stop application containers while retaining the portable database volume and backups:

```bash
podman compose --env-file .env.portable \
  -f compose.yaml -f compose.portable.yaml down
```

Stop PostgreSQL separately only when it is no longer needed:

```bash
podman stop de_warehouse
```

Do not use `podman compose down --volumes` or delete the dated migration backup.

## Rebuild after code changes

Rebuild all service images:

```bash
podman compose --env-file .env.portable \
  -f compose.yaml -f compose.portable.yaml build
```

Rebuild and restart:

```bash
podman compose --env-file .env.portable \
  -f compose.yaml -f compose.portable.yaml up --build -d
```

The Containerfiles optimize rebuilds by copying Maven POM files before source code.
Dependency layers can therefore be reused when only Java source or static assets change.

## Data and safety

- `northstar_bank_postgres` is the PostgreSQL container used by the running application.
- `de_warehouse` is retained only as the migration source until cleanup is verified.
- The four service databases remain separate.
- One existing PostgreSQL login is used by the services.
- `runtime/migration-portable-20261028/` contains the private rollback dumps created
  before migration. Existing backup directories were not modified.
- The gateway is the only exposed application service by default.
- `.env` and `.env.portable` contain local credentials and secrets and must remain untracked.

This is a local learning/demo environment, not a production banking system.


### Stop without removing containers

Use `podman compose --env-file .env.portable -f compose.yaml -f compose.portable.yaml stop`
to retain application containers.
The current Compose file declares no persistent Kafka volume; removing the Kafka container
can lose broker data in its writable layer. PostgreSQL is managed separately. Plan Kafka
persistence before relying on broker data surviving container recreation.

### Documentation update — 28 October 2026

Added the visual roadmap, current blocker, milestones and handoff process. Preserved all
original README content in the linked snapshot. Documented the portable PostgreSQL
container, four-database migration, private rollback dumps, and the post-migration
runtime. Credentials remain local and are not documented.
