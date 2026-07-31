# Service Learning Documentation Contract

## 1. Special-scenario architecture

Choose only scenarios that are module-specific or have meaningful system-design value. For each scenario include:

1. Business background and data characteristics.
2. Core conflict and constraints.
3. Candidate approaches and trade-offs.
4. Current implementation with concrete classes, methods, SQL, Lua, keys, jobs, or messages.
5. Concurrency, consistency, idempotency, failure, retry, and degradation behavior.
6. Current limitations and large-scale evolution.

Avoid retelling every user feature. Link the design back to affected features.

## 2. Business inventory

Begin with service positioning, boundaries, user roles, and a feature table. For each user-facing feature include:

1. Feature description.
2. Business rules.
3. User interaction flow.
4. Data flow in business language.
5. Only genuinely needed extra sections: permissions, visibility, state changes, exceptions, moderation, consistency expectation, or implementation status.

Exclude RPC names, request-field tables, response structures, JSON examples, return-field explanations, and error-code catalogs. Background jobs are supporting mechanisms, not independent product features.

## 3. Business-flow walkthrough

This is the primary code-learning document. Start with a complete feature and maintenance-flow inventory and organize it by business lifecycle.

For each feature or tightly coupled group include:

1. Business goal and trigger.
2. Entry, normalization, and validation.
3. Ordered call chain with real class and method names, followed by a numbered step-by-step narrative that explains how execution moves through the chain.
4. For every numbered step, describe input/state, the condition or branch taken, the invoked method, DB/cache/MQ/RPC changes, and what is passed to the next step; include short decisive Java, SQL, Lua, configuration, or pseudocode excerpts.
5. Database, Redis, MQ, scheduled-job, and cross-service effects.
6. Transaction boundary and consistency model.
7. Idempotency, concurrency, pagination, retry, and degradation behavior.
8. Result assembly and user-visible outcome.
9. Implementation status, deviations, or risks.

Finish with storage inventory, asynchronous-flow summary, implementation-status table, and feature-to-code index. Prefer excerpts exposing decisions over complete copied methods.

## 4. Interview guide

Base project questions on verified implementation. Organize by subsystem and include:

- core question and concise answer;
- why the approach fits the data;
- consistency and failure behavior;
- likely follow-up questions;
- boundary and evolution at larger scale;
- a prioritized review checklist;
- a reusable answer structure.

Do not claim measured results without evidence. Label generic industry options as evolution ideas rather than current implementation.

## Final quality gate

- Current code wins over old documents.
- Implemented, partial, stubbed, configured-but-unused, and absent behavior are distinct.
- No obsolete Redis keys, jobs, schemas, or direct-MQ claims remain.
- No two documents contain long duplicated explanations.
- Every feature has a detailed numbered execution process; a call-chain diagram, feature summary, or architecture explanation does not satisfy this requirement.
- A reader can understand the full implementation from the business-flow walkthrough alone.

