---
name: generate-service-business-docs
description: "Analyze a backend service from its actual code, protobuf/API definitions, database migrations, configuration, jobs, messaging, and nearby design notes, then generate two coordinated Markdown documents: a code-level feature walkthrough and a grouped natural-language business-flow explanation. Use when documenting another service in this repository, refreshing service documentation after code changes, or producing documents modeled on the match-service course notes."
---

# Generate Service Business Docs

Generate two evidence-based documents for one service. Treat executable code as the source of truth and use design notes only to supply intent or clearly labeled planned behavior.

## Workflow

1. Locate repository instructions such as `AGENTS.md` and obey them.
2. Identify the service source directory, protobuf/API definitions, database migrations, configuration, tests, and the target documentation directory.
3. Read the target directory's existing `README.md`, PRD, knowledge notes, and related design documents.
4. If the user provides reference documents, extract their structure and useful presentation patterns without copying inaccuracies or accidental repetition.
5. Build a feature inventory from public API entries, background jobs, message producers/consumers, and important internal maintenance flows.
6. Trace every feature through `entry → service → manager/repository → storage/cache/MQ/RPC → response or retry`.
7. Group features that share one business lifecycle. Keep distinct behavior visible inside the group.
8. Generate:
   - `<service>-业务.md`: code-level steps, branches, keys, tables, transaction boundaries, failure handling, and return values.
   - `<service> 业务流程详解.md`: natural-language explanation grouped by user journey or subsystem.
9. Compare every factual statement against code before finishing.

Read [references/documentation-contract.md](references/documentation-contract.md) before drafting either document.

## Evidence Rules

- Prefer current source code over PRDs, comments, old course notes, build logs, or aspirational design documents.
- Cite concrete class and method names in the code-level document.
- Distinguish four states explicitly: implemented, stubbed/TODO, configured but unused, and designed but absent.
- Do not claim concurrency, caching, batching, transactions, retries, TTLs, limits, or fallback behavior unless the implementation shows them.
- When code and design disagree, document actual behavior and add a short “实现现状/差异” note.
- Preserve questionable implementation behavior as evidence; do not silently rewrite it into an ideal design.
- Call out correctness or operability risks only when supported by code, and explain the exact mechanism.

## Grouping Rules

- Group create/read/delete operations under one content lifecycle when they share the same entity.
- Group like/comment counters when they share the same Redis write-behind architecture, but retain separate API and persistence steps.
- Group feed retrieval, fanout, cold-start insertion, and pool rebuilding under content distribution when they form one delivery system.
- Keep cross-service clients, scheduled jobs, and MQ consumers as first-class flows rather than burying them in footnotes.

## Validation

- Ensure the feature inventory covers every public RPC/API method.
- Ensure every scheduled job and MQ producer/consumer appears in at least one document.
- Verify table and Redis key names character-for-character.
- Verify enum meanings against protobuf and Java comparisons; flag inversions or ambiguous defaults.
- Verify pagination cursor semantics and `hasMore` calculation.
- Verify transaction annotations apply to an effective proxy boundary; do not infer a transaction only from a helper method annotation.
- Check that both documents describe the same feature set and implementation status.
