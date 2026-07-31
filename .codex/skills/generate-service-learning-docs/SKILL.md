---
name: generate-service-learning-docs
description: Analyze a backend service from its current source code, API or protobuf definitions, database migrations, configuration, scheduled jobs, messaging, cross-service clients, tests, PRDs, and existing course notes, then consolidate them into four coordinated Chinese learning documents covering special-scenario architecture, a product-oriented business inventory without RPC request/response details, a complete code-backed business-flow walkthrough, and interview preparation. Use when organizing duplicated module-learning notes, documenting a backend service for systematic study, refreshing course documents after implementation changes, merging PRD and technical walkthrough content, or generating materials that explain both business behavior and key code implementation.
---

# Generate Service Learning Docs

Generate four evidence-based, non-overlapping Chinese learning documents for one backend service. Treat executable code as the source of truth; use PRDs and old notes only to discover intent, terminology, and topics that require verification.

## Workflow

1. Locate and obey repository instructions such as `AGENTS.md`.
2. Identify the service source directory, public API or protobuf definitions, database migrations, configuration, tests, scheduled jobs, message producers and consumers, cross-service clients, and target documentation directory.
3. Read all existing PRDs, course notes, business documents, flow explanations, architecture notes, and interview notes in the target directory.
4. Build an evidence inventory of user-facing features, background flows, architectural scenarios, storage, messages, jobs, dependencies, and implementation status.
5. Trace each feature through `entry → validation → service → manager/repository → DB/cache/MQ/RPC/job → result, retry, or degradation`.
6. Compare existing documents with current code. Remove obsolete claims instead of blending historical and current implementations.
7. Read [references/documentation-contract.md](references/documentation-contract.md), then generate or refresh exactly four coordinated documents:
   - `<service>-特殊场景设计.md`
   - `<service>-业务清单.md`
   - `<service>-业务流程详解.md`
   - `<service>-面试考点.md`
8. Remove superseded documents only when their useful content has been incorporated and the user asked to consolidate or reduce duplication.
9. Validate coverage and consistency.

## Evidence Rules

- Use this precedence: executable source; migrations/API/configuration/tests; current design notes; PRD; historical notes.
- Do not infer concurrency, transactions, batching, retries, TTLs, limits, fallback behavior, or implementation status from comments alone.
- Verify transaction proxy boundaries, enum numeric values, SQL predicates, Redis keys, cursor advancement, `hasMore`, retry limits, job schedules, and cache expiration.
- Distinguish “cache written” from “cache read”, “field reserved” from “feature supported”, and “message produced” from “consumer can complete”.
- Mark behavior as implemented, partially implemented, stubbed/TODO, configured-but-unused, or designed-but-absent.
- When code and product intent disagree, document actual behavior and add a concise implementation-status note.
- Preserve supported risks. Do not rewrite questionable code into an ideal architecture.
- Never invent production QPS, latency improvements, incident outcomes, or capacity numbers.

## Duplication Rules

Give every fact one primary home:

- Business inventory: what users can do and the governing rules.
- Business-flow walkthrough: how each feature executes in code.
- Special scenarios: why cross-cutting architecture exists and its trade-offs.
- Interview guide: how to explain and defend the design.

Use short cross-references instead of copying whole sections. Each document must remain understandable on its own.

## Validation

- Cover every public API and user-visible function in the business inventory and walkthrough.
- Cover every scheduled job, producer, consumer, and meaningful maintenance flow in the walkthrough.
- Ensure special scenarios are genuinely distinctive or architecturally instructive.
- Ensure all four documents describe the same implementation status.
- Ensure the business inventory contains no RPC schema, request/response table, response JSON, or error-code catalog.
- Require every walkthrough feature to contain a complete call chain plus a numbered, step-by-step execution narrative covering every important branch, state change, transaction/commit point, failure path, and final result; a flow arrow or summary alone is insufficient.
- Ensure each walkthrough feature contains enough key code and explanation to understand its complete path.
- Ensure interview answers start from business/data characteristics, explain trade-offs, cover failure and consistency boundaries, and distinguish project facts from evolution options.
- Search the final directory for obsolete keys, removed jobs, stale architecture, and duplicated headings.

