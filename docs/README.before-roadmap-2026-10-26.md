# Northstar Demo Bank

Northstar Demo Bank is a local Spring Boot microservice banking sandbox. It demonstrates
identity and session management, account and ledger operations, payments, Kafka events,
notifications, role-based authorization, and a browser dashboard.

The project runs in WSL Ubuntu with rootless Podman. PostgreSQL is an existing container
and is deliberately kept outside the application Compose project.

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

Existing PostgreSQL container:
de_warehouse
   |
   +--> bank_identity
   +--> bank_ledger
   +--> bank_payment
   +--> bank_notification
```

Only `gateway-service` publishes an application port. The other Java services and Kafka
are reachable only inside the Podman network. PostgreSQL remains an existing container
published on the host at port `5432`.

Rootless Podman cannot attach the `slirp4netns` PostgreSQL container to the application
network. The Java services therefore connect to PostgreSQL through:

```text
host.containers.internal:5432
```

No Compose command creates or drops PostgreSQL databases, roles, volumes, or backups.

## Project structure

```text
.
├── common/                    # Shared Java client and internal authentication code
├── gateway-service/           # Public web UI and API gateway
├── identity-service/          # Users, login, sessions, and roles
├── ledger-service/            # Accounts, balances, transfers, entries, and outbox
├── payment-service/           # Payments, idempotency, and ledger calls
├── notification-service/      # Kafka consumer and notification persistence
├── deploy/                    # Deployment-related assets and backup references
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
| PostgreSQL | Existing `de_warehouse` | 5432 | Host-published |

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
- An existing PostgreSQL container named `de_warehouse`
- The PostgreSQL container published on host port `5432`
- The existing PostgreSQL username and password
- Internet access when pulling base images or rebuilding images

Check the required tools:

```bash
podman --version
podman-compose --version
podman ps -a
```

The existing database container should appear as `de_warehouse`. Do not connect it to
`demobank-net`; rootless `slirp4netns` does not support that operation.

## First-time setup

Run these commands from:

```bash
cd /home/srieubuntu/projects/demobank
```

Create the application network if it does not exist:

```bash
podman network exists demobank-net || podman network create demobank-net
```

Create the local environment file:

```bash
cp .env.example .env
```

Edit `.env` and set the credentials that already exist in `de_warehouse`:

```env
BANK_DB_USER=de_admin
BANK_DB_PASSWORD=your_existing_database_password
BANK_INTERNAL_SECRET=your_long_random_internal_secret
DB_HOST=host.containers.internal
DB_PORT=5432
PODMAN_NETWORK=demobank-net
GATEWAY_BIND_ADDRESS=127.0.0.1
GATEWAY_PORT=8080
```

Generate an internal secret if needed:

```bash
openssl rand -hex 48
```

Keep `.env` local. It is excluded by `.gitignore` and must not be committed.

## Start the demo

Start the existing PostgreSQL container:

```bash
podman start de_warehouse
```

Build images if this is the first run or source has changed, then start the application:

```bash
podman compose --env-file .env -f compose.yaml up --build -d
```

For a normal restart when images are already current:

```bash
podman compose --env-file .env -f compose.yaml up -d
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

Customer registration is exposed through `POST /api/users`. Administrator activation is
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
podman compose --env-file .env -f compose.yaml logs -f gateway-service
```

Follow all application logs:

```bash
podman compose --env-file .env -f compose.yaml logs -f
```

Restart the application containers:

```bash
podman compose --env-file .env -f compose.yaml restart
```

Stop the application stack while retaining containers, images, database data, and
backups:

```bash
podman compose --env-file .env -f compose.yaml down
```

Stop PostgreSQL separately only when it is no longer needed:

```bash
podman stop de_warehouse
```

Do not use `podman compose down --volumes`, database drop commands, or backup deletion
commands in this learning environment.

## Rebuild after code changes

Rebuild all service images:

```bash
podman compose --env-file .env -f compose.yaml build
```

Rebuild and restart:

```bash
podman compose --env-file .env -f compose.yaml up --build -d
```

The Containerfiles optimize rebuilds by copying Maven POM files before source code.
Dependency layers can therefore be reused when only Java source or static assets change.

## Data and safety

- `de_warehouse` is the source of PostgreSQL data for this deployment.
- The four service databases remain separate.
- One existing PostgreSQL login is used by the services.
- Compose does not initialize, restore, migrate, drop, or delete database backups.
- The gateway is the only exposed application service by default.
- `.env` contains local credentials and secrets and must remain untracked.

This is a local learning/demo environment, not a production banking system.
