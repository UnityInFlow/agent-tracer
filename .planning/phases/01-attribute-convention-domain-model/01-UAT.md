---
status: complete
phase: 01-attribute-convention-domain-model
source: [01-VERIFICATION.md]
started: 2026-06-14T00:00:00Z
updated: 2026-06-14T00:00:00Z
---

## Current Test

[testing complete]

## Tests

### 1. Contract vs implementation: four Recommended gen_ai.* keys are dropped at extraction
expected: |
  `gen_ai.provider.name`, `gen_ai.agent.id`, `gen_ai.tool.call.id`, and
  `gen_ai.conversation.id` are declared Recommended in docs/agent-span-convention.md §2
  and have agent-semconv constants, but AgentSpanExtractor reads none of them and
  AgentSpan has no field for them. A KORE span carrying gen_ai.conversation.id is
  silently dropped. Resolution: either extend AgentSpan + extractor to read these four
  keys, OR annotate the four doc rows as recognised-but-not-extracted-in-v1.0 so the
  Stable contract does not over-promise. (Matches code-review WR-01.)
result: issue
reported: "Decided together: extend AgentSpan + extractor to read all four keys so the code matches the Stable contract. Three of the four map to named v1 acceptance criteria (filters by agent via gen_ai.agent.id, by model/provider via gen_ai.provider.name, subagent/tool hierarchy via gen_ai.tool.call.id) and gen_ai.conversation.id underpins grouping spans into a run. Change is purely additive and nothing is published yet."
severity: major

## Summary

total: 1
passed: 0
issues: 1
pending: 0
skipped: 0
blocked: 0

## Gaps

- truth: "A span carrying gen_ai.provider.name, gen_ai.agent.id, gen_ai.tool.call.id, or gen_ai.conversation.id is extracted into typed AgentSpan fields, matching the published Stable contract (docs/agent-span-convention.md §2)."
  status: failed
  reason: "User reported: decided to extend AgentSpan + extractor to read all four Recommended keys. The agent-semconv constants exist (GenAiAttributes.PROVIDER_NAME, AGENT_ID, TOOL_CALL_ID, CONVERSATION_ID) and the contract lists all four as Recommended, but AgentSpan has no corresponding fields and AgentSpanExtractor never reads them. Confirmed by code-review WR-01 and by reading the source."
  severity: major
  test: 1
  artifacts:
    - backend/src/main/kotlin/io/github/unityinflow/agenttracer/domain/AgentSpan.kt
    - backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractor.kt
    - agent-semconv/src/main/kotlin/io/github/unityinflow/agentsemconv/GenAiAttributes.kt
    - docs/agent-span-convention.md
  missing:
    - "AgentSpan: 4 nullable fields — provider (String?, gen_ai.provider.name), agentId (String?, gen_ai.agent.id), toolCallId (String?, gen_ai.tool.call.id), conversationId (String?, gen_ai.conversation.id)"
    - "AgentSpanExtractor: read each of the four keys via the existing GenAiAttributes constants (string coercion, absence -> null, no unityinflow.* precedence needed — these are gen_ai.* only)"
    - "Tests: extend the gen_ai-only and value-type test classes to assert the four fields populate from a gen_ai span and are null when absent"
