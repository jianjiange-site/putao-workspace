# Service Documentation Contract

## Code-level document

Start with a feature inventory table. For each feature or tightly related feature group, include:

1. Entry point and request fields.
2. Parameter normalization and validation.
3. Ordered call chain with real class and method names.
4. Database, Redis, MQ, scheduled-job, and cross-service effects.
5. Transaction boundary and consistency model.
6. Idempotency, concurrency, retry, and degradation behavior.
7. Response fields and pagination semantics.
8. Implementation status, deviations, or risks when relevant.

Use compact Java/SQL/pseudocode excerpts. Prefer short excerpts that expose decisions and branches over long copied methods.

Finish with:

- data model and Redis/MQ inventory;
- scheduled and asynchronous flow summary;
- implementation-status and risk table;
- feature-to-code index.

## Natural-language document

Organize by business journey rather than by class:

1. Service role and boundaries.
2. Content creation and lifecycle.
3. Content reading and interaction.
4. Distribution and recommendation.
5. Asynchronous maintenance and eventual consistency.
6. Failure/degradation behavior.
7. Current implementation status and key takeaways.

Explain why each mechanism exists and what the user experiences. Keep method names only where they improve traceability.

## Required distinctions

- “Cache written” is not “cache read.”
- “Fields reserved” is not “feature supported.”
- “Producer sends” is not “consumer can complete” when dependencies are stubs.
- “Code comments say parallel/batch/transactional” is not proof; verify execution.
- Proto enum numeric defaults matter. Compare generated enum values with server-side conditionals.
- A shared dirty-set processed by multiple independent jobs needs explicit concurrency analysis.
- Redis `GET+SET 0` protects only the Redis reset; explain what happens if the following database update fails.

## Tone

Write in clear Chinese unless the user requests another language. Explain technical terms with the business consequence nearby. Avoid presenting proposed architecture as completed implementation.
