# Northstar Demo Bank

Northstar Demo Bank is a local Spring Boot microservice banking sandbox. It demonstrates
identity and session management, account and ledger operations, payments, Kafka events,
notifications, role-based authorization, and a browser dashboard.

The project runs in WSL Ubuntu with rootless Podman. The portable deployment packages
PostgreSQL with the application and initializes the four service databases on a named
volume.


## Project roadmap and where to resume

Open the [visual roadmap](docs/roadmap.html). Upcoming work is listed first; accomplishments
and historical context are at the end. Updated **29 October 2026**.

**Current baseline:** portable PostgreSQL and all five application services are running.
The latest check returned UP for gateway, identity, ledger and notifications; gateway
login, account retrieval, payment history and notification retrieval were also verified.
The four bank databases were migrated into `northstar_bank_postgres`, rollback dumps
were preserved, and the old four bank databases were removed from `de_warehouse`.
Shared warehouse databases and backups were preserved.

**Current signup status:** new customers remain pending until a six-digit email OTP is
verified. WSL Postfix is configured as the local SMTP service on port 25; the application
uses `host.containers.internal` to reach it from Podman. Local verification covered signup,
activation, single-use OTP rejection, and case-insensitive duplicate-email rejection.

### Upcoming milestones — one small step at a time

The 50 seeded customers remain demo fixtures. They are not a limit on registrations.
The goal is reliable signup for actual people using the demo, with fictional funds;
this plan does not establish production banking readiness.

#### 1 — Complete: Reliable new-customer signup

Audit the existing POST /api/users flow before extending it. Allow a previously unknown email to create a CUSTOMER with a unique ID and hashed password; validate input and normalized email uniqueness, including concurrent duplicate submissions. Keep pre-seeded activation distinct. Provide clear success/error states and persist the user across restarts.

**Done when:** A person not among the 50 demo users can register, sign in, and see only their own profile and empty account state; duplicate signup cannot create extra users or an admin.

#### 2 — Complete locally: Email ownership verification

Pending signups use BCrypt password hashes and code digests, with ten-minute expiry, five
attempts, sixty-second resend cooldown, and single-use activation. The browser provides
verify and resend actions. Expired-code and automated rate-limit regression tests remain
follow-up work.

**Done when:** Signup, verification, expired link, resend and duplicate-click tests pass. Tokens and credentials are absent from logs; the UI accurately distinguishes pending and verified users.

#### 3: Welcome and transaction emails

Extend notifications with email delivery, keeping existing in-app notifications. Send welcome mail after verification and transaction mail after a committed successful transfer. Plan durable delivery records, retries, duplicate suppression and visible failed-delivery status. Keep delivery failures from rolling back committed transfers.

**Done when:** Local captured emails reach the correct test recipient once logically; outages retry without duplicate business actions. External delivery is enabled only after controlled inbox testing and provider configuration.

#### 4: Small user onboarding pilot

Keep the 50 deterministic customers as optional demo fixtures, not a signup limit or a substitute for new users. Add a demo-seeding switch; disabling it must not delete existing users. Onboard a small invited group at a steady pace and record signup, verification and delivery failures before expanding.

**Done when:** A few invited users complete signup and verification repeatedly with no manual database edits. New users do not inherit demo balances, passwords or administrator roles. This remains a demo with fictional funds.

#### 5: Reliability and regression baseline

Close outstanding migration record checks and transfer/idempotency/notification tests. Verify volume persistence on recreation, Kafka persistence, readiness, resource budgets and backup restore. Track source and add focused signup, authorization and email regression tests.

**Done when:** Recorded test evidence covers data integrity, customer isolation, restart recovery, email failure recovery and stable resource use. Cleanup of old bank resources is recorded only when verified.

#### Later: Pods and further features

Revisit pods, deployment expansion, password recovery and optional payment prioritization after the onboarding flow is stable. Define each as a separate small milestone.

**Done when:** Scope and acceptance checks are agreed before implementation; no calendar commitments or production-readiness claims are implied.

Keep the existing setup, architecture and operations sections below as the runbook.
After each session, record changed files, evidence, unfinished work and one next action
in both documents. Move a milestone to accomplishments only after its checks pass.

## Current architecture

```text
Browse
   |
   | http://<host-lan-ip>:8080
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
   +--> bank_ledge
   +--> bank_payment
   +--> bank_notification
```

Only `gateway-service` publishes an application port. The other Java services, Kafka and
PostgreSQL are reachable only inside the Podman network. The Java services connect to the
portable database using the Compose service name `postgres`. The portable Compose file
binds the gateway to all host interfaces by default for trusted-LAN demo access.

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
├── compose.portable.yaml      # Complete portable Podman Compose stack
├── .env.portable.example      # Portable environment template
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
./start-demo.sh
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
./stop-demo.sh
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
GATEWAY_BIND_ADDRESS=0.0.0.0
GATEWAY_PORT=8080
```

Generate an internal secret if needed:

```bash
openssl rand -hex 48
```

Keep `.env.portable` local. It is excluded by `.gitignore` and must not be committed.

## Start the demo

The portable PostgreSQL container is the application database. The start helpe
validates `.env.portable`, creates the external application network if needed,
validates Compose without printing secrets, starts the standalone stack, and waits
for the gateway health endpoint.

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

The portable deployment is configured for localhost access. Open:

```env
http://127.0.0.1:8080
```

VS Code Port Forwarding remains the approved access path and must stay **Private**.
Do not configure Windows port forwarding, router forwarding, or LAN-wide exposure.
The portable database password and internal service secret must remain local and
must not be used with real banking data or real customer credentials.

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
POST   /api/users/verify
POST   /api/users/resend
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

## Local WSL SMTP and OTP delivery

WSL Ubuntu uses Postfix on TCP port 25 and OpenDKIM for OTP messages. Postfix accepts
SMTP only from `127.0.0.0/8`, the private Podman subnet `10.89.0.0/24`, and Podman's
host gateway `10.255.255.254`; it rejects
unauthorized relaying. The Compose application reaches the host with
`SMTP_HOST=host.containers.internal`, without SMTP credentials or STARTTLS because the
connection stays inside the local WSL/Podman boundary.

Check the services:

```bash
sudo systemctl status postfix opendkim
sudo postconf mynetworks smtpd_relay_restrictions smtpd_recipient_restrictions
```

The current sender is `no-reply@northstar.local`. This is suitable for local testing,
but `.local` is not an Internet email domain. For real recipients, use a domain you
control, update `EMAIL_FROM`, generate a DKIM key for that domain, and publish DNS:

```text
 SPF at @:        v=spf1 ip4:<public-mail-sender-ip> -all
 DKIM at mail._domainkey: v=DKIM1; k=rsa; p=<contents of mail.txt>
 DMARC at _dmarc: v=DMARC1; p=quarantine; rua=mailto:dmarc@<your-domain>
```

SPF and DKIM records cannot be validly published for `northstar.local` or a private
LAN address. Do not expose port 25 to the LAN or Internet, and do not add broad networks
to `mynetworks`; use a managed SMTP relay for production email.

## Logs and operations

Follow gateway logs:

```bash
podman compose --env-file .env.portable \
  -f compose.portable.yaml logs -f gateway-service
```

Follow all application logs:

```bash
podman compose --env-file .env.portable \
  -f compose.portable.yaml logs -f
```

Restart the application containers:

```bash
podman compose --env-file .env.portable \
  -f compose.portable.yaml restart
```

Stop application containers while retaining their containers, the portable database
volume, and backups:

```bash
./stop-demo.sh
```

Do not use `podman compose down --volumes` or delete the dated migration backup.

## Rebuild after code changes

Rebuild all service images:

```bash
podman compose --env-file .env.portable \
  -f compose.portable.yaml build
```

Rebuild and restart:

```bash
podman compose --env-file .env.portable \
  -f compose.portable.yaml up --build -d
```

The Containerfiles optimize rebuilds by copying Maven POM files before source code.
Dependency layers can therefore be reused when only Java source or static assets change.

## Data and safety

- `northstar_bank_postgres` is the PostgreSQL container used by the running application.
- `de_warehouse` is retained for shared warehouse data; the four old `bank_*`
  databases were removed after migration verification.
- The four service databases remain separate.
- One existing PostgreSQL login is used by the services.
- `runtime/migration-portable-20261028/` contains the private rollback dumps created
  before migration. Existing backup directories were not modified.
- The gateway is the only exposed application service and is localhost-bound by default.
- `.env.portable` contains local credentials and secrets and must remain untracked.

This is a local learning/demo environment, not a production banking system.


### Stop without removing containers

Use `./stop-demo.sh` to retain application containers.
The current Compose file declares no persistent Kafka volume; removing the Kafka containe
can lose broker data in its writable layer. PostgreSQL is managed separately. Plan Kafka
persistence before relying on broker data surviving container recreation.

### Documentation update — 29 October 2026

Added the visual roadmap, current blocker, milestones and handoff process. Preserved all
original README content in the linked snapshot. Documented the portable PostgreSQL
container, four-database migration, private rollback dumps, and the post-migration
runtime. Added WSL Postfix/OpenDKIM OTP delivery guidance, local relay restrictions, and
real-domain SPF/DKIM/DMARC prerequisites. Credentials remain local and are not documented.


## Accomplishments and project history

Completed implementation is separated from checks that still need evidence.

- **Foundation — September 2026:** Java 21 / Spring Boot microservices, shared module,
  browser UI, separate schemas, ledger, idempotent payments and Kafka outbox implemented.
- **Feature expansion:** registration and pre-seeded activation code, 50 deterministic
  demo customers, administrator role checks, saved UI theme, pending customer signup,
  single-use OTP verification, resend throttling and SMTP delivery implemented locally.
- **WSL and Podman:** project migrated to WSL; service Containerfiles and Compose added.
- **Portable database — 28 October 2026:** migration documented, new PostgreSQL container
  running and four private rollback dumps present. Gateway and all four internal service
  health endpoints independently returned UP. Full record/workflow verification and
  cleanup are not claimed complete by these health checks.
- **Local transactional email — 29 October 2026:** WSL Postfix and OpenDKIM are active;
  Postfix accepts only loopback, the private Podman subnet and its host gateway, and
  rejects unauthorized relay. A signup OTP was delivered locally with a DKIM signature.
- **Project continuity:** visual roadmap and README summary created; historical README
  preserved. Upcoming milestones now lead, and accomplishments appear last.

Historical dates/authorship are reconstructed where no Git evidence was available.
See the visual roadmap for the retained timeline. The documents before this planning
update are preserved in `docs/archive/before-signup-plan-20261028/`.
