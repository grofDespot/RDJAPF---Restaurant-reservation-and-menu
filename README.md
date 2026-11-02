# Restaurant reservation and menu (P05)

Individual project for *Rapid Development of Java Applications Using Programming Frameworks*, 2026/27.
Author: Dominik Despot.

Guests reserve tables for a time slot, and the kitchen maintains a menu with allergen information. An AI
feature proposes allergens and diet tags from a dish description, and a chef must confirm them before guests
see them.

> **Status: Milestone 1** — domain model, first ADR, and an empty project running against PostgreSQL in Docker.

## Run it

Requirements: JDK 25, Maven 3.9+ (IntelliJ's bundled Maven is fine), and Docker Desktop.

```bash
# Option A: everything in Docker (database + application)
docker compose up --build
# -> http://localhost:8080 and http://localhost:8080/actuator/health  (db: UP)

# Option B: database in Docker, application from IntelliJ / Maven
docker compose up -d db
mvn spring-boot:run

# Tests (Testcontainers starts its own PostgreSQL; Docker must be running)
mvn verify
```

## Technology

| Area | Choice |
|---|---|
| Runtime | Java 25, Spring Boot 4.1 (Spring Framework 7, Spring Security 7, Hibernate 7) |
| Web | Spring MVC + Thymeleaf, REST with springdoc-openapi 3 (from M3) |
| Database | PostgreSQL 18, schema and seed data with Flyway, HikariCP |
| Tests | JUnit 6, Mockito, Testcontainers 2 |
| Build / CI | Maven, GitHub Actions, multi-stage Dockerfile |

## Roles and access

Guests do not log in. The menu with the allergen and diet filter, the availability search and the booking form are public.

| Role | Can do |
|---|---|
| ADMIN | Restaurants, tables and adjacency, opening hours, user accounts |
| HOST | All reservations: view, edit, cancel, mark no-show |
| CHEF | Menus and dishes; the only role that confirms or rejects AI allergen and diet suggestions |

Limitation: guests cannot cancel online; they contact the restaurant and a host cancels for them.

## Decisions (M1)

| Decision | Choice |
|---|---|
| Hosting | Render (web service from the Dockerfile) + Neon (managed PostgreSQL). Neither needs a payment card. |
| AI provider | _to be confirmed_ |
| Persistence model | [ADR-0001](docs/adr/0001-persistence-model-reservations-and-tables.md) |

## Documentation

- [Domain model and entity diagram](docs/domain-model.md)
- [Architecture decision records](docs/adr/)
- Figma prototype: _link added with the wireframes_

## Configuration

| Profile | Datasource |
|---|---|
| default | Local Docker database (`localhost:5432/restaurant`). Overridable with `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`. |
| `prod` | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` and `PORT` from the environment, with no defaults. Secrets live only in the Render dashboard. |
