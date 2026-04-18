# ADR 0008 - Library as Polymorphic Read-Only Collection

**Status:** Accepted

## Context

The media library is a large, scanner-populated dataset. Users never create or modify library entities directly -- all mutations originate from the file-system scanner. Previous designs carried OCC (optimistic concurrency control), lease logic, and cleanup triggers that added complexity without matching the actual write pattern.

## Decision

The `library` bounded context is modeled as a **read-only collection** from the application's perspective.

### Data model

A polymorphic root table `entities` holds shared columns (id, name, sort_name, created_at, updated_at). Subtype tables -- `artists`, `albums`, `audio_tracks` -- extend it with type-specific columns. Supporting tables include `genres` and `images`, linked to entities.

The production schema structure (from the database dump) serves as the reference for column names, indexes, and constraints.

### Write path

Only the `infra-library-scanner` worker writes to the library schema (`core_v2_library`). There are no user-facing commands, no aggregates with OCC, and no lease mechanism.

### Cleanup

Database-level cleanup triggers have been removed. The scanner runs a scheduled reconciliation job that detects orphaned entities (files no longer on disk) and deletes them in bulk.

### Search

Full-text search is implemented via PostgreSQL trigram indexes (`pg_trgm`) on name fields, providing fuzzy matching without an external search engine.

## Consequences

- Simpler domain model: no command handlers, no version columns, no conflict resolution for library entities.
- Scanner is the single source of truth; the application layer exposes query ports only.
- Removing triggers eliminates hidden side effects and makes the cleanup schedule explicit and observable.
- Trigram indexes keep search self-contained in PostgreSQL, avoiding operational overhead of a separate search service.
- Any future user-editable metadata (e.g., manual tag corrections) would need to live in a separate context or be reconciled with scanner writes.
