# Domain model — P05 Restaurant reservation and menu

The rendered diagram is in [`domain-model.png`](domain-model.png). The Mermaid source below is the master copy:
GitHub renders it directly, and the PNG is regenerated from it.

## Entity diagram

```mermaid
erDiagram
    RESTAURANT ||--o{ OPENING_HOURS : "is open"
    RESTAURANT ||--o{ RESTAURANT_TABLE : has
    RESTAURANT ||--o{ RESERVATION : takes
    RESTAURANT ||--o{ MENU : offers
    RESERVATION ||--|{ RESERVATION_TABLE : occupies
    RESTAURANT_TABLE ||--o{ RESERVATION_TABLE : "booked in"
    RESTAURANT_TABLE ||--o{ TABLE_ADJACENCY : "is next to"
    MENU ||--o{ DISH : contains
    DISH ||--o{ DISH_ALLERGEN : "contains"
    ALLERGEN ||--o{ DISH_ALLERGEN : "found in"
    DISH ||--o{ DISH_DIET_TAG : "suits"
    DIET_TAG ||--o{ DISH_DIET_TAG : "applies to"
    APP_USER |o--o{ DISH_ALLERGEN : "reviewed by"
    APP_USER |o--o{ DISH_DIET_TAG : "reviewed by"

    APP_USER {
        bigint id PK
        varchar username UK
        varchar password_hash "BCrypt"
        varchar full_name
        varchar email
        varchar role "ADMIN | HOST | CHEF"
        boolean enabled
    }
    RESTAURANT {
        bigint id PK
        varchar name
        varchar address
        varchar phone
        int turn_duration_minutes "how long a booking holds a table"
        bigint version "optimistic lock"
    }
    OPENING_HOURS {
        bigint id PK
        bigint restaurant_id FK
        smallint day_of_week "1 = Monday ... 7 = Sunday"
        time opens_at
        time closes_at
    }
    RESTAURANT_TABLE {
        bigint id PK
        bigint restaurant_id FK
        varchar label "UK with restaurant_id, e.g. T4"
        int seats
        boolean active
    }
    TABLE_ADJACENCY {
        bigint table_id PK, FK
        bigint adjacent_table_id PK, FK
    }
    RESERVATION {
        bigint id PK
        bigint restaurant_id FK
        varchar guest_name "guests book without an account"
        varchar guest_email
        varchar guest_phone
        int party_size
        timestamp starts_at
        timestamp ends_at "starts_at + turn duration, frozen at booking"
        varchar status "BOOKED | CANCELLED | COMPLETED | NO_SHOW"
        varchar note
        timestamp created_at
        bigint version "optimistic lock"
    }
    RESERVATION_TABLE {
        bigint reservation_id PK, FK
        bigint table_id PK, FK
    }
    MENU {
        bigint id PK
        bigint restaurant_id FK
        varchar name
        boolean active
    }
    DISH {
        bigint id PK
        bigint menu_id FK
        varchar name
        text description "free text, input to the AI feature"
        numeric price
        boolean available
        bigint version "optimistic lock"
    }
    ALLERGEN {
        bigint id PK
        varchar code UK "GLUTEN, MILK, ... (EU 14)"
    }
    DIET_TAG {
        bigint id PK
        varchar code UK "VEGAN, VEGETARIAN, ..."
    }
    DISH_ALLERGEN {
        bigint id PK
        bigint dish_id FK "UK with allergen_id"
        bigint allergen_id FK
        varchar source "CHEF | AI"
        varchar status "SUGGESTED | CONFIRMED | REJECTED"
        bigint reviewed_by FK "nullable"
        timestamp reviewed_at "nullable"
    }
    DISH_DIET_TAG {
        bigint id PK
        bigint dish_id FK "UK with diet_tag_id"
        bigint diet_tag_id FK
        varchar source "CHEF | AI"
        varchar status "SUGGESTED | CONFIRMED | REJECTED"
        bigint reviewed_by FK "nullable"
        timestamp reviewed_at "nullable"
    }
```

## Aggregates

| Aggregate | Root → children | Full CRUD on screens + REST |
|---|---|---|
| Restaurant (main) | Restaurant → RestaurantTable, OpeningHours, Reservation | yes |
| Menu | Menu → Dish → DishAllergen / DishDietTag | screens needed for the chef and the AI review |

The spec names the chain *Restaurant → RestaurantTable → Reservation*. A reservation is attached to the
restaurant and *occupies* one or more tables through `RESERVATION_TABLE`. A single-table booking has one row
there, and a combined booking (advanced requirement 3) has several. The reasoning is in
[ADR-0001](adr/0001-persistence-model-reservations-and-tables.md).

## Entity notes

- **AppUser** – staff accounts only. Guests never log in. The menu, the allergen and diet filter, the
  availability search and the booking form are public, and a booking stores the guest's contact details.
  There are three staff roles with genuinely different capabilities:
  - `ADMIN` manages restaurants, tables, adjacency, opening hours and users.
  - `HOST` manages all reservations: view, edit, cancel, mark no-show.
  - `CHEF` manages menus and dishes and is the only role that confirms or rejects allergen and diet suggestions.

  Accepted limitation: a guest cannot cancel online, only by contacting the restaurant.
- **Restaurant.turnDurationMinutes** – configurable per restaurant (advanced requirement 1).
- **OpeningHours** – a separate entity rather than an embedded value. A day may have several rows, such as
  lunch and dinner, and the availability query joins it. Limitation: a slot cannot cross midnight.
- **TableAdjacency** – a plain self many-to-many on `RestaurantTable`. Both directions are stored (A→B and B→A)
  so that "neighbours of T" is a single indexed lookup. The service layer keeps the pairs symmetric.
- **Reservation**
  - `endsAt` is stored rather than computed. If the turn duration changes later, existing bookings stay
    where they are. The overlap check becomes the index-friendly `starts_at < :end AND ends_at > :start`.
  - `@Version` handles concurrent edits of the same reservation.
  - Two people booking the *same table* at the same moment is handled by a pessimistic lock on the chosen
    table rows inside the booking transaction (ADR-0001).
- **Times** – `LocalDateTime` in the restaurant's local time (one application-wide zone, Europe/Zagreb), stored
  as `timestamp without time zone`.
- **Allergen / DietTag** – reference data seeded by Flyway. Only the `code` is stored. Display names come from
  `messages_hr.properties` / `messages_en.properties`, so i18n needs no extra tables.
- **DishAllergen / DishDietTag** – association entities *with attributes*. `status` is the safety rule from the
  topic:
  - The AI writes rows as `SUGGESTED`.
  - Only a `CHEF` turns them into `CONFIRMED` or `REJECTED`.
  - Guest-facing queries read `CONFIRMED` rows only.
  - `REJECTED` rows are kept so the AI does not propose the same thing again, and they feed
    `docs/ai-evaluation.md`.

## Coverage of the LO4 requirements

| Requirement | Where |
|---|---|
| One-to-many | Restaurant → RestaurantTable, Restaurant → Reservation, Menu → Dish, … |
| Many-to-many with an association entity carrying attributes | Dish ↔ Allergen via `DishAllergen` (source, status, reviewer, time) |
| Optional relation | `DishAllergen.reviewedBy` / `DishDietTag.reviewedBy` (empty until a chef reviews an AI suggestion) |
| Plain many-to-many | Reservation ↔ RestaurantTable, RestaurantTable ↔ RestaurantTable (adjacency) |
| N+1 candidate to find, fix and document | Menu page: dishes → confirmed allergens (fix with a fetch join or entity graph) |
| Lazy vs eager | Everything `LAZY` by default. Joins are explicit per use case, and `spring.jpa.open-in-view=false`. |

## How the advanced requirements use the model

1. **Availability search.** For a restaurant, date and party size:
   - Generate candidate start times from `OPENING_HOURS` so that a slot plus the turn duration still ends
     before closing.
   - A table is free when no `BOOKED` reservation in `RESERVATION_TABLE` overlaps the slot.
   - Planned index: `reservation (restaurant_id, starts_at)` plus the primary key of `reservation_table`
     (`table_id` first). This is the "hard part" and the target of the performance measurement.
2. **Allergen and diet filter.** One query with a `NOT EXISTS` subquery for "contains none of these allergens"
   and a grouped `EXISTS … HAVING COUNT = n` for "has all of these diet tags". Both look only at `CONFIRMED`
   rows. Built with JPA Criteria / Spring Data `Specification` so any combination of criteria stays one query.
3. **Combining adjacent tables.** When no single free table seats the party, search connected groups of free
   tables in the `TABLE_ADJACENCY` graph (breadth-first, smallest group first) whose seats add up to the party
   size. The result is one reservation with several `RESERVATION_TABLE` rows.

## Open questions for the topic acceptance meeting

- Which aggregate must have full CRUD over REST: Restaurant → Table → Reservation only, or Menu → Dish as well?
- P05 does not describe a domain scheduled job (LO7). Proposed: a nightly job that marks past `BOOKED`
  reservations as `COMPLETED`, with no-shows set by staff. The second job is a reminder for reservations
  tomorrow, which writes a log line or notification.
