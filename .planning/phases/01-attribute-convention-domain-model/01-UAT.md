---
status: testing
phase: 01-attribute-convention-domain-model
source: [01-VERIFICATION.md]
started: 2026-06-14T00:00:00Z
updated: 2026-06-14T00:00:00Z
---

## Current Test

number: 1
name: Decide whether the four published-but-dropped contract attributes belong in AgentSpan for v1.0, or whether the convention doc rows should be marked not-yet-extracted.
expected: |
  Either AgentSpan gains provider/agentId/toolCallId/conversationId fields the
  extractor reads, OR docs/agent-span-convention.md §2 annotates those four rows
  as recognised-but-not-extracted-in-v1.0 so the contract does not over-promise.
awaiting: user response

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
result: [pending]

## Summary

total: 1
passed: 0
issues: 0
pending: 1
skipped: 0
blocked: 0

## Gaps
