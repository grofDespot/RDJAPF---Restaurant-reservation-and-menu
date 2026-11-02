# ADR-0001: Persistence model — a reservation occupies tables through a join table

- **Status:** Accepted
- **Date:** 2026-11-02
- **Milestone:** M1

## Context

The topic names the aggregate *Restaurant → RestaurantTable → Reservation*. Three requirements constrain how
that chain is stored:

1. **Availability search** (advanced requirement 1) must answer "is table T free between *start* and *end*?" for
   every table of a restaurant. The topic calls keeping it fast with a full reservation book "the hard part".
2. **Combining adjacent tables** (advanced requirement 3) means one booking can hold *several* tables at once.
3. **Concurrency.** Two guests can try to book the same table for the same evening at the same moment, and
   exactly one of them must win.

A strict parent-child chain (`reservation.table_id NOT NULL`) handles 1 and 3 well, but it cannot express 2
without extra structure.

## Decision

1. `Reservation` belongs to `Restaurant` (`reservation.restaurant_id`, many-to-one, required).
2. `Reservation` ↔ `RestaurantTable` is a **plain many-to-many** through the join table
   `reservation_table (reservation_id, table_id)`, with the primary key ordered `(table_id, reservation_id)`.
   A normal booking has one row there, and a combined booking has several.
3. `Reservation` stores **`ends_at`** next to `starts_at`. It is computed once at booking time as
   `starts_at + restaurant.turn_duration_minutes`.
4. Table adjacency is a plain self many-to-many, `table_adjacency (table_id, adjacent_table_id)`, with both
   directions stored.
5. Double booking is prevented in the service layer. Inside the booking transaction:
   - lock the chosen `restaurant_table` rows with `SELECT … FOR UPDATE` (`@Lock(PESSIMISTIC_WRITE)`), in
     ascending id order so that two transactions cannot deadlock;
   - re-run the overlap check for those tables;
   - insert the reservation.

   `@Version` on `Reservation` handles concurrent *edits* of the same booking.
6. Schema and seed data are owned by **Flyway** (`spring.jpa.hibernate.ddl-auto=validate`). PostgreSQL is used
   in every environment except unit tests: Docker locally, Testcontainers in integration tests, and Neon in
   production.

The REST and screen hierarchy still follows the named chain:
`/restaurants/{id}/tables` and `/restaurants/{id}/reservations`. The join table is a persistence detail.

## Alternatives considered

| Alternative | Why it was rejected |
|---|---|
| `reservation.table_id` (one table) plus a separate `combined_tables` collection | Two places to look when checking availability. Every overlap query would need an `OR` across both, which is harder to index and easy to get wrong. |
| A `TableGroup` entity with predefined combinations (e.g. "T4+T5"), with the reservation pointing to a table or a group | Staff would have to maintain every combination by hand, and the topic asks for *automatic* combination. Overlap checks would need to expand groups into tables anyway. |
| Computing `ends_at` in the query (`starts_at + turn_duration * interval '1 minute'`) | Not usable by a plain index. Changing a restaurant's turn duration would silently move every existing booking. |
| A PostgreSQL exclusion constraint (`EXCLUDE USING gist (table_id WITH =, period WITH &&)`) on `reservation_table` | The strongest guarantee, but it needs a `tsrange` column duplicated from the reservation, the `btree_gist` extension, and a custom Hibernate type. Too much machinery for this project. Kept as the "what I would do next" item. |
| `ddl-auto=update` / Hibernate-generated schema | Forbidden by the specification for the final version, and it hides the real DDL that the N+1 and index work needs to show. |

## Consequences

- **Accepted:**
  - The relationship from a table to its reservations is many-to-many rather than a pure one-to-many. The
    one-to-many requirement of LO4 is met by Restaurant → Table, Restaurant → Reservation and Menu → Dish.
  - Every reservation write touches two tables, so creating and changing a reservation must happen in one
    `@Transactional` service method.
- **Gained:**
  - One query shape answers availability for single and combined bookings alike:
    `… JOIN reservation_table rt … WHERE rt.table_id IN (:tables) AND r.status = 'BOOKED' AND r.starts_at < :end AND r.ends_at > :start`.
  - It is directly indexable and is the subject of the performance measurement in M4.
  - Existing bookings are unaffected when a restaurant changes its turn duration.
- **Risk:**
  - The pessimistic lock serialises bookings for the *same* tables. That is acceptable for the expected load
    of one restaurant, and bookings for different tables are not blocked.
  - The lock only works if every booking path goes through the same service method. The screen, the REST
    endpoint and table combination all call one entry point, `ReservationService.book(...)`, and an
    integration test fires two concurrent bookings at it.
