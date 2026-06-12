# Claude Code Harness Engineering — Comprehensive Setup Guide v2

> Based on [HumanLayer: Skill Issue — Harness Engineering for Coding Agents](https://www.humanlayer.dev/blog/skill-issue-harness-engineering-for-coding-agents)
> Extended with [GSD](https://github.com/gsd-build/get-shit-done), [RTK](https://github.com/rtk-ai/rtk), and [Superpowers](https://github.com/obra/superpowers).
> Tailored for Kotlin/Spring Boot microservices projects on Hetzner/Docker/Kubernetes.

---

## Core Mental Model

```
coding agent = AI model(s) + harness
```

Harness engineering is **not** about better models — it's a configuration problem.
Every time your agent fails, engineer a solution so it never fails that way again.

### The Nine Harness Levers

| Lever | Tool / Approach | Purpose | When to Add |
|---|---|---|---|
| `CLAUDE.md` | Hand-written | Deterministic system prompt injection | Day 1, every project |
| Token Compression | **RTK** | Reduce CLI output noise 60-90% before it hits context | Day 1 — install globally |
| MCP Servers | Context7, sequential-thinking | Extend capabilities with tools | Only when CLI doesn't exist |
| Skills | Manual + **Superpowers** | Progressive knowledge disclosure | When instructions bloat system prompt |
| Sub-agents | Native + **GSD** | Context firewall / isolation | When tasks require many tool calls |
| Spec Workflow | **GSD** | Phase-gated planning + fresh contexts per task | When quality degrades mid-session |
| Hooks | Bash scripts | Deterministic control flow | When the agent repeats the same mistake |
| Back-pressure | Gradle checks, JaCoCo | Self-verification mechanisms | Highest leverage — invest early |
| PR Artifacts | ADR + docs + issues + tests | Complete, traceable delivery per PR | Every PR, non-negotiable |

### The "Dumb Zone" Problem

Context rot is real and empirically backed (Chroma research). Performance degrades
as context length increases. Every irrelevant grep result, file read, or tool call
that ends up in the parent thread is a distractor — and distractor effects **compound**.

**Solution strategy:**
- Keep the parent thread lean (orchestration only)
- Delegate noise-heavy work to sub-agents (or GSD phases)
- Compress CLI output with RTK before it ever enters context
- Make success silent, surface only failures

---

## 0. RTK — Token Compression (Install First)

RTK (Rust Token Killer) is a CLI proxy that intercepts command output before it
reaches the model and compresses it by 60–90%. Install this before anything else —
it works silently and makes every other lever more effective.

### Install

```bash
# Install binary
cargo install --git https://github.com/rtk-ai/rtk
# or via pre-built binary
curl -fsSL https://rtk-ai.app/install.sh | sh

# Verify — MUST show token savings, not "command not found"
rtk gain
```

### Wire into Claude Code (global)

```bash
rtk init -g          # Installs PreToolUse hook + RTK.md + patches settings.json
rtk init --show      # Verify hook is active
```

This adds `.claude/hooks/rtk-rewrite.sh` — a `PreToolUse` hook that transparently
rewrites shell commands through RTK before execution. Claude never needs to change
its behaviour; the hook fires automatically.

### What RTK Compresses for Kotlin/Spring Boot

| Raw Command | RTK Equivalent | Typical Savings |
|---|---|---|
| `git status` | `rtk git status` | ~95% |
| `git log --oneline` | `rtk git log` | ~80% |
| `git diff` | `rtk git diff` | ~70% |
| `./gradlew test` | pass-through + failure tee | Full output saved to file on failure |
| `./gradlew build` | pass-through | n/a — Gradle output is signal-rich |
| `kubectl get pods` | `rtk kubectl pods` | ~85% |
| `docker ps` | `rtk docker ps` | ~85% |
| `grep`/`find`/`ls` | `rtk grep` / `rtk find` / `rtk ls` | ~75% |
| `gh pr list` | `rtk gh pr list` | ~80% |

**Gradle note:** Gradle build/test output is dense with useful diagnostics —
let it pass through unfiltered and rely on the tee feature for failures:

```toml
# ~/.config/rtk/config.toml
[tee]
mode = "failures"   # full output saved to file only on non-zero exit
max_files = 20
```

On failure the agent sees:
```
FAILED: 3/42 tests [full output: ~/.local/share/rtk/tee/1707753600_gradlew_test.log]
```
It reads the file once instead of re-running the test suite.

### Monitor Savings

```bash
rtk gain            # total token savings
rtk gain --graph    # ASCII graph by command
```

---

## 1. CLAUDE.md / AGENTS.md

### Rules (from the ETH Zurich study + HumanLayer experience)

- ✅ Human-written only — LLM-generated files **hurt** performance by 20%+
- ✅ Under 60 lines
- ✅ Only universally applicable rules — conditional rules compound badly
- ✅ Include build commands, hard rules, CLI examples
- ❌ No directory listings or repo overviews — agents discover structure on their own
- ❌ No "always use tool X for Y" over-steering
- ❌ No auto-generated content

### Template for Kotlin/Spring Boot Projects

Create at `.claude/CLAUDE.md` (or repo root `CLAUDE.md`):

```markdown
## Project
Spring Boot microservice in Kotlin + Coroutines. PostgreSQL via Spring Data JPA.
Kafka for event streaming. Maven multi-module build.

## Build Commands
- `./mvnw verify` — compile + test + lint
- `./mvnw test` — tests only
- `./mvnw test -Dgroups=UnitTest` — fast unit tests (~10s)
- `./mvnw detekt:check` — lint
- `./mvnw spring-boot:run` — start locally
- `./mvnw jacoco:report` — coverage report

## Hard Rules
- Always write tests for new public functions
- Never run Flyway migrations directly — ask the user
- Use coroutines (suspend functions), never Thread.sleep or blocking calls
- Keep functions under 30 lines; extract helpers when needed
- Never commit secrets or credentials
- Every PR needs: tests, ADR (if architectural), updated docs, linked GH issue

## GitHub
- Use `gh issue create` to create issues; link every PR to its issue
- Use `gh pr create --fill --draft` — PRs start as drafts
- ADRs live in `docs/adr/` — use `gh issue` to discuss before writing

## Sub-agents
For research, codebase exploration, or tracing flows across services — always
spawn a sub-agent. Return condensed answers with filepath:line citations only.
```

---

## 2. MCP Servers

### The Golden Rule

> If a well-known CLI exists (gh, docker, kubectl, mvn) — use the CLI.
> The model already knows it from training data. MCP is for capabilities that have no CLI.

### Recommended Minimal Set (enable per-project, not globally)

```bash
# Install via Claude Code settings or /mcp add
context7           # live, version-specific library docs
sequential-thinking # structured multi-step reasoning
playwright         # browser automation — only if project has E2E tests
```

### What NOT to Install Globally

| MCP Server | Better Alternative |
|---|---|
| Linear MCP (200+ tools) | Custom Linear CLI wrapper (6 commands) |
| GitHub MCP | `gh` CLI — Claude already knows it |
| Docker MCP | `docker` CLI |
| PostgreSQL MCP | `psql` CLI |
| Slack MCP | Slack webhook via `curl` in a hook |

### Security Warning

Never connect to untrusted MCP servers. Tool descriptions are injected into your
system prompt — this is a prompt injection vector. STDIO servers running via
`npx` / `uvx` can execute arbitrary code on your host.

---

## 3. Skills

### Two Approaches

**A) Manual skills directory** — full control, project-specific, committed to repo.  
**B) Superpowers plugin** — battle-tested skills library installed globally, auto-activating.

Use both: Superpowers for universal engineering skills, manual skills for project-specific patterns.

### A) Superpowers Plugin (Global Engineering Skills)

```bash
# In Claude Code
/plugin marketplace add obra/superpowers-marketplace
/plugin install superpowers@superpowers-marketplace
```

Superpowers provides auto-activating skills including:
- `test-driven-development` — activates when implementing features
- `systematic-debugging` — activates when debugging issues  
- `verification-before-completion` — activates before claiming work is done
- `code-review` — activates when reviewing changes
- `subagent-driven-development` — two-stage review (spec compliance, then quality)
- `brainstorm` / `write-plan` / `execute-plan` — slash commands for planning workflows

Skills activate automatically when relevant — no manual `/skill` invocation needed.
They are loaded via a SessionStart hook that injects the bootstrap into every session.

### B) Manual Skills Directory (Project-Specific)

```
.claude/
├── CLAUDE.md                    # always loaded
└── skills/
    ├── api-feature/
    │   ├── SKILL.md             # 7-phase feature development workflow
    │   └── response_template.md
    ├── kafka-consumer/
    │   ├── SKILL.md
    │   └── consumer_template.kt
    ├── db-migration/
    │   ├── SKILL.md
    │   └── migration_template.sql
    ├── code-review/
    │   └── SKILL.md
    ├── debugging/
    │   └── SKILL.md
    ├── pr-artifacts/            # NEW — see Section 9
    │   ├── SKILL.md
    │   ├── adr_template.md
    │   └── pr_checklist.md
    └── infra-deploy/
        ├── SKILL.md
        └── k8s_template.yaml
```

### Skill Activation

```bash
/skill api-feature       # loads SKILL.md into context as a user message
/skill kafka-consumer
/skill code-review
/skill pr-artifacts      # activates before opening a PR
```

### Example: `api-feature/SKILL.md`

```markdown
# API Feature Development Skill
Follow this 7-phase workflow for new API features.

## Phases
1. **Discovery** — read existing similar endpoints; identify patterns
2. **Exploration** — use a sub-agent to trace the full request/response flow
3. **Clarifying Questions** — surface ambiguities before writing any code
4. **Architecture** — define contract (request/response DTOs), service boundaries
   → If decision has architectural impact: create ADR draft in docs/adr/
5. **Implementation** — controller → service → repository; test each layer
6. **Quality Review** — run `./mvnw detekt:check test -Dgroups=UnitTest` and fix all issues
7. **PR Preparation** — write changelog entry, update OpenAPI spec, run /skill pr-artifacts

## Response Template
See `response_template.md` in this skill's directory.
```

### Example: `kafka-consumer/SKILL.md`

```markdown
# Kafka Consumer Skill
You are implementing a Kafka consumer in this Spring Boot/Kotlin project.

## Pattern
Use `@KafkaListener` with coroutine scope via `runBlocking { }` wrapper.
The canonical implementation is in `consumer_template.kt` in this skill's directory.

## Checklist
- [ ] Dead letter topic configured
- [ ] Retry policy with exponential backoff
- [ ] Idempotency guard (check for duplicate message IDs)
- [ ] Structured logging with traceId
- [ ] Integration test using `EmbeddedKafkaBroker`

## What NOT to do
- Never use blocking calls without a coroutine scope
- Never commit offsets manually unless explicitly asked
- Never swallow exceptions silently
```

### Example: `code-review/SKILL.md`

```markdown
# Code Review Skill
You are performing a code review for this Kotlin/Spring Boot codebase.

## Review Checklist
- [ ] Coroutine usage correct (no blocking calls in suspend context)
- [ ] Error handling — no silent catches, proper logging
- [ ] Test coverage — new public functions have tests
- [ ] No hardcoded configuration — use @ConfigurationProperties
- [ ] SQL queries reviewed for N+1 issues
- [ ] Kafka producers use transactions if required
- [ ] No credentials or secrets in code
- [ ] Function length < 30 lines; complexity reasonable

## Output Format
Return: summary, blocking issues (must fix), suggestions (nice to have).
Cite each issue as `filepath:line — description`.
```

---

## 4. Sub-Agents

### When to Use Sub-Agents

| Task Type | Why Sub-Agent? |
|---|---|
| "Find all usages of pattern X" | Many file reads — noise stays isolated |
| "Trace a Kafka event across services" | Cross-service grep floods parent context |
| "Research how library Y handles Z" | Web + doc browsing fills context fast |
| "Implement isolated module" | Full implementation stays encapsulated |
| "Analyze codebase patterns for refactor" | Many reads, simple condensed answer needed |
| "Write ADR for this decision" | Background research + structured output |

### Cost Optimization

```
Parent session  →  Opus (planning, orchestration, complex reasoning)
Sub-agents      →  Sonnet or Haiku (discrete, bounded tasks)
```

Sub-agents receive small, well-defined tasks that don't need the most powerful model.
Never burn Opus tokens on a codebase grep.

### Sub-Agent Instructions (add to CLAUDE.md)

```markdown
## Sub-agents
For research, codebase exploration, or tracing flows across services — always
spawn a sub-agent rather than doing it inline. Sub-agents must return:
- A direct, concise answer to the question
- Source citations in `filepath:line` format or URLs
- No raw tool call output — summarize and condense only
```

### Sub-Agent System Prompt Template

When configuring a sub-agent, always define:

```markdown
## Role
You are a [research / implementation / analysis] sub-agent.

## What you SHOULD do
- [specific bounded task]
- Return a condensed answer with filepath:line citations

## What you SHOULD NOT do
- Do not implement changes (research sub-agent)
- Do not spawn further sub-agents
- Do not return raw grep output — summarize it

## Output Format
Max 20 lines. Lead with the direct answer. Follow with citations.
```

---

## 5. GSD — Spec-Driven Workflow (Fresh Contexts Per Phase)

GSD is the workflow orchestration layer. It solves context rot at the system level:
each phase runs in a fresh 200K context window via isolated sub-agents. Your main
session stays at 30–40% usage while heavy work happens in clean environments.

### Install

```bash
npx get-shit-done@latest
# Select: Claude Code, Global or Local, your model profile
```

### Core Workflow for a New Feature

```bash
# 1. Bootstrap (once per project)
/gsd:new-project            # Interview → PRD → roadmap; configure phases

# 2. Per-phase loop (fresh /clear between each)
/clear
/gsd:discuss-phase 1        # Lock in preferences, surface ambiguities
/gsd:plan-phase 1           # Research + plan + Nyquist verification gate
/gsd:execute-phase 1        # Parallel sub-agent execution
/gsd:verify-work 1          # Goal-backward UAT (what must be TRUE?)
/gsd:ship 1                 # Creates PR from verified work

# 3. Quick tasks (no full workflow needed)
/gsd:quick "Add DAF-1234 correlation ID to audit log"
/gsd:quick --research "Evaluate VictoriaLogs vs Loki hot/cold"
```

### GSD Phase → PR Artifacts Mapping

| GSD Phase | Artifact Produced | Where it goes |
|---|---|---|
| `discuss-phase` | DISCUSSION-LOG.md (decisions) | `.planning/` |
| `plan-phase` | PLAN.md (tasks + test matrix) | `.planning/phases/N/` |
| `execute-phase` | code + atomic commits per task | git history |
| `verify-work` | verification report | `.planning/phases/N/VERIFY.md` |
| `ship` | PR with linked issue | GitHub |
| ADR (manual) | docs/adr/NNNN-title.md | repo docs |

The `ship` phase does not auto-create ADRs or documentation updates — those are
produced by the plan/execute phases via the `pr-artifacts` skill (see Section 9).

### Model Profile for Kotlin/Spring Boot

```json
// .planning/config.json (configure via /gsd:settings)
{
  "model_profile": "balanced",
  "agents": {
    "research": "claude-haiku",
    "planner": "claude-sonnet",
    "executor": "claude-sonnet",
    "verifier": "claude-sonnet",
    "orchestrator": "claude-opus"
  }
}
```

### What GSD Gives You That Manual Workflows Don't

- **Fresh context per phase** — no accumulating rot across the feature lifecycle
- **Atomic git commits per task** — `git bisect` works; each task independently revertable
- **Nyquist validation gate** — plan-phase verifies test feedback loop exists before writing code
- **Goal-backward verification** — verify-work asks "what must be TRUE?" not "what did we do?"
- **Session forensics** — `/gsd:forensics` diagnoses stuck loops and corrupted state
- **Context threads** — `/gsd:note` captures cross-session knowledge

### GSD + Hermes Pipeline Integration

For automated Slack-triggered workflows on Orange Pi:

```bash
# Headless mode — GSD works with --dangerously-skip-permissions
claude --dangerously-skip-permissions \
  "$(cat .planning/phases/1/PLAN.md)" \
  | /gsd:execute-phase 1
```

Wire `on-stop.sh` to post GSD's verify report back to Slack.

---

## 6. Hooks

### Hook Event Types (Claude Code)

| Event | Use Case |
|---|---|
| `PostToolCall` | Typecheck/lint after every file change |
| `PreBash` | Block dangerous commands before execution; RTK rewrites here |
| `Stop` | Run test suite, coverage check, create PR |
| `Notification` | Slack/sound alerts when attention needed |

### Directory Structure

```
.claude/
├── settings.json
└── hooks/
    ├── rtk-rewrite.sh          # managed by RTK — do not edit
    ├── post-tool-call.sh       # typecheck + lint (runs after every file write)
    ├── pre-bash.sh             # block dangerous commands
    └── on-stop.sh              # coverage check + PR creation + Slack
```

### `settings.json`

```json
{
  "hooks": {
    "preToolUse": [
      { "command": ".claude/hooks/rtk-rewrite.sh" }
    ],
    "postToolCall": [
      { "command": ".claude/hooks/post-tool-call.sh" }
    ],
    "preBash": [
      { "command": ".claude/hooks/pre-bash.sh" }
    ],
    "stop": [
      { "command": ".claude/hooks/on-stop.sh" }
    ]
  }
}
```

### `post-tool-call.sh` — Kotlin/Maven

```bash
#!/bin/bash
cd "$CLAUDE_PROJECT_DIR"

# Run Detekt + compile in parallel, silent on success
OUTPUT=$(./mvnw detekt:check compile -q 2>&1)

if [ $? -ne 0 ]; then
  echo "Build/lint errors:" >&2
  echo "$OUTPUT" >&2
  exit 2   # exit 2 = re-engage agent to fix errors before finishing
fi

# SUCCESS: completely silent — nothing added to context
```

### `pre-bash.sh` — Block Dangerous Commands

```bash
#!/bin/bash
COMMAND="$CLAUDE_TOOL_INPUT_COMMAND"

# Block direct Flyway migration runs
if echo "$COMMAND" | grep -qE "flyway:migrate|flywayMigrate|migrate --env"; then
  echo "ERROR: Do not run migrations directly. Ask the user to run them." >&2
  exit 1
fi

# Block production deployments
if echo "$COMMAND" | grep -qE "deploy.*(prod|production)|helm upgrade.*prod"; then
  echo "ERROR: Production deployments require explicit human approval." >&2
  exit 1
fi

# Block force-push
if echo "$COMMAND" | grep -q "git push --force\|git push -f"; then
  echo "ERROR: Force push is not allowed. Use --force-with-lease and confirm with user." >&2
  exit 1
fi

# Block dropping databases
if echo "$COMMAND" | grep -qiE "drop (database|schema)"; then
  echo "ERROR: Dropping databases requires human confirmation." >&2
  exit 1
fi
```

### `on-stop.sh` — Coverage + PR Creation + Slack

```bash
#!/bin/bash
cd "$CLAUDE_PROJECT_DIR"

# 1. Run fast unit tests silently — surface errors only
TEST_OUTPUT=$(./mvnw test -Dgroups=UnitTest -q 2>&1)
if [ $? -ne 0 ]; then
  echo "Unit tests failed:" >&2
  echo "$TEST_OUTPUT" >&2
  exit 2
fi

# 2. Coverage check — re-engage agent if it drops
COVERAGE=$(./mvnw jacoco:check -q 2>&1)
if echo "$COVERAGE" | grep -q "Coverage checks have not been met"; then
  echo "Coverage dropped below threshold. Increase test coverage before finishing." >&2
  exit 2
fi

# 3. Verify PR artifact checklist exists — re-engage if missing
BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [[ "$BRANCH" == feature/* ]] || [[ "$BRANCH" == DAF-* ]]; then
  # Check that at minimum a GH issue is linked
  ISSUE_LINKED=$(git log origin/main..HEAD --format="%s %b" | grep -cE "#[0-9]+|DAF-[0-9]+")
  if [ "$ISSUE_LINKED" -eq 0 ]; then
    echo "No GitHub issue or Jira ticket linked in commit history. Add before finishing." >&2
    exit 2
  fi

  # Create PR if it doesn't exist
  gh pr create --fill --draft 2>/dev/null || true
fi

# 4. Slack notification (Hermes pipeline)
# curl -s -X POST "$SLACK_WEBHOOK" \
#   -H "Content-Type: application/json" \
#   -d "{\"text\": \"Claude finished task on $BRANCH\"}" > /dev/null
```

---

## 7. Back-Pressure (Verification Mechanisms)

This is the **highest-leverage investment** in your harness. Agent success rate
directly correlates with its ability to verify its own work.

### The Golden Rule

> **Success must be silent. Only failures produce output.**

4,000 lines of passing tests in context = agent loses track of task and hallucinates.
RTK + the tee feature enforce this automatically for most commands.

### Back-Pressure Stack for Kotlin/Spring Boot

```bash
# Tier 1: Instant feedback (run via PostToolCall hook, < 5 seconds)
./mvnw detekt:check compile -q

# Tier 2: Fast tests (run via Stop hook, < 15 seconds)
./mvnw test -Dgroups=UnitTest -q

# Tier 3: Coverage gate (run via Stop hook)
./mvnw jacoco:check -q

# Tier 4: GSD verify-work (goal-backward UAT, run before /gsd:ship)
/gsd:verify-work N

# Tier 5: Full suite (run manually or in CI only — never auto in agent sessions)
./mvnw verify
```

### JaCoCo Configuration (`pom.xml`)

```xml
<plugin>
  <groupId>org.jacoco</groupId>
  <artifactId>jacoco-maven-plugin</artifactId>
  <executions>
    <execution>
      <id>check</id>
      <goals><goal>check</goal></goals>
      <configuration>
        <rules>
          <rule>
            <limits>
              <limit>
                <counter>LINE</counter>
                <value>COVEREDRATIO</value>
                <minimum>0.80</minimum>
              </limit>
            </limits>
          </rule>
        </rules>
      </configuration>
    </execution>
  </executions>
</plugin>
```

### Detekt Baseline

Keep a `detekt-baseline.xml` committed so new code violations are flagged without
blocking on pre-existing issues:

```bash
./mvnw detekt:baseline   # generate baseline once
./mvnw detekt:check      # subsequent runs only flag NEW violations
```

---

## 8. What Doesn't Work

| Anti-Pattern | Why It Fails |
|---|---|
| Designing the ideal harness upfront | You haven't hit real failures yet — waste of time |
| Installing 20+ skills/MCPs "just in case" | Bloats system prompt, pushes into dumb zone |
| Running full test suite after every change | 5+ min / 4000 lines floods context with noise |
| Micro-optimizing which sub-agents access which tools | Tool thrash → worse results |
| Auto-generating CLAUDE.md | LLM-written ones hurt performance by 20%+ |
| "Just use a longer context window" | Bigger haystack, same needle-finding ability |
| Skipping GSD discuss-phase | Claude makes assumptions → more iteration later |
| Using GSD full workflow for a typo fix | Massive overkill — use `/gsd:quick` or just prompt directly |
| Not installing RTK | Every git/kubectl/grep burns tokens you'll never get back |

---

## 9. PR Artifact Standard

Every PR must include all of the following before moving from Draft → Review.
Wire this into the `pr-artifacts` skill so the agent produces it automatically.

### `.claude/skills/pr-artifacts/SKILL.md`

```markdown
# PR Artifacts Skill
You are preparing a pull request for this Kotlin/Spring Boot codebase.
Produce ALL of the following before calling /gsd:ship or opening a PR for review.

## 1. GitHub Issue
- Verify the PR is linked to a GH issue: `gh issue view <N>` or create one:
  `gh issue create --title "DAF-XXXX: <summary>" --body "..."`
- Set the issue reference in the PR body: "Closes #N"

## 2. ADR (Architecture Decision Record)
Required when the PR introduces or changes:
- A new framework, library, or runtime dependency
- An infrastructure or deployment topology change
- A security or authentication pattern
- A data model or schema decision with long-term impact
- A cross-service contract (Kafka topic, REST API, event schema)

Not required for: bug fixes, refactors within existing patterns, test additions.

Use the template at `adr_template.md`. File as `docs/adr/NNNN-short-title.md`.
NNNN = next sequential number. Commit with message: `docs(adr): NNNN short title`

## 3. Documentation Update
Update whichever of the following applies:
- `docs/` — API docs, runbooks, architecture diagrams
- `README.md` — setup instructions, environment variables
- OpenAPI spec (`openapi.yaml` / `src/main/resources/openapi/`) — if REST contract changed
- `CHANGELOG.md` — one-line entry under Unreleased: `- DAF-XXXX: <summary>`

## 4. Tests
Verify the following before shipping:
- [ ] Unit tests for every new public function (`*Test.kt` in same package)
- [ ] Integration test for any new endpoint or Kafka consumer
- [ ] Test names follow: `should <expected behaviour> when <condition>`
- [ ] `./mvnw test -Dgroups=UnitTest` passes silently
- [ ] `./mvnw jacoco:check` passes (80% minimum)

## 5. Verification Report
Produce a brief checklist in the PR description:
- [ ] All unit tests pass
- [ ] Coverage ≥ 80%
- [ ] Detekt clean (no new violations)
- [ ] ADR written (or explicitly not required — state why)
- [ ] Docs updated
- [ ] GH issue linked
- [ ] Manual smoke test: describe what you tested and the outcome

## PR Description Template
\`\`\`
## Summary
[1-2 sentences: what changed and why]

## Changes
- [bullet: concrete change]
- [bullet: concrete change]

## Verification
- [ ] Unit tests pass
- [ ] Coverage ≥ 80%
- [ ] Detekt clean
- [ ] ADR: docs/adr/NNNN-title.md (or: not required because ...)
- [ ] Docs updated: [which files]
- [ ] Issue: Closes #N

## Manual Testing
[What you ran and what you observed]
\`\`\`
```

### `.claude/skills/pr-artifacts/adr_template.md`

```markdown
# NNNN. [Short Title]

Date: YYYY-MM-DD
Status: [Proposed | Accepted | Deprecated | Superseded by NNNN]
Jira: DAF-XXXX

## Context
[What is the situation? What problem are we solving? What constraints exist?]

## Decision
[What did we decide to do?]

## Consequences

### Positive
- [benefit]

### Negative / Trade-offs
- [cost or risk]

### Neutral
- [side effect]

## Alternatives Considered
[What else was evaluated and why was it rejected?]
```

### Automate ADR Creation via Sub-Agent

Add this to `.claude/commands/adr.md`:

```markdown
You are creating an Architecture Decision Record for this Kotlin/Spring Boot project.

1. Use a sub-agent to research the decision context by reading the relevant code,
   existing ADRs in docs/adr/, and the current GH issue if one is open.
2. Number the new ADR: `ls docs/adr/ | sort | tail -1` → increment NNNN.
3. Use the template at `.claude/skills/pr-artifacts/adr_template.md`.
4. Write the ADR in a sub-agent with a clean context — do not inline it.
5. Output the final file path and first 10 lines as confirmation.
6. Create a GH issue for discussion: `gh issue create --label adr --title "ADR NNNN: title"`
```

---

## 10. Project Setup Checklist

### Day 1 (Every New Project)
- [ ] Install RTK globally: `rtk init -g` — verify with `rtk gain`
- [ ] Install Superpowers: `/plugin install superpowers@superpowers-marketplace`
- [ ] Write `CLAUDE.md` — human-written, under 60 lines, build commands + hard rules
- [ ] Enable only 2-3 MCP servers relevant to the project
- [ ] Create `.claude/hooks/pre-bash.sh` — block dangerous commands
- [ ] Create `.claude/hooks/post-tool-call.sh` — typecheck/lint, silent on success
- [ ] Register hooks in `.claude/settings.json`
- [ ] Set up fast unit test subset (`-Dgroups=UnitTest`, ~10-15s max)
- [ ] Install GSD: `npx get-shit-done@latest` (global, Claude Code)

### After First Agent Failures
- [ ] Create skill directories for patterns that repeat
- [ ] Wire coverage check to Stop hook
- [ ] Add `on-stop.sh` for PR creation + issue link check
- [ ] Configure GSD model profile via `/gsd:settings`
- [ ] Add `pr-artifacts` skill and ADR template

### Per-PR Workflow
- [ ] Open/link GH issue before starting
- [ ] `/gsd:new-project` or `/gsd:discuss-phase N` — surface assumptions first
- [ ] `/gsd:plan-phase N` — plan with Nyquist gate
- [ ] `/gsd:execute-phase N` — parallel execution in fresh contexts
- [ ] `/skill pr-artifacts` — produce ADR (if needed), doc updates, test verification
- [ ] `/gsd:verify-work N` — goal-backward UAT
- [ ] `/gsd:ship N` — creates draft PR with linked issue

### Team Distribution
- [ ] Commit `.claude/` directory to repo — configs apply to whole team
- [ ] Document skills in README or team wiki
- [ ] Review and prune hooks every few sprints — throw away what isn't helping
- [ ] Share RTK config via `~/.config/rtk/config.toml` snippet in team wiki

---

## 11. Quick Reference

### Key Exit Codes for Hooks

| Exit Code | Meaning |
|---|---|
| `0` | Success — silent, nothing added to context |
| `1` | Failure — message printed but agent NOT re-engaged |
| `2` | Failure — agent re-engaged to fix the issue before finishing |

### Context Budget Priority (highest to lowest)

1. Task instruction (user message)
2. CLAUDE.md / AGENTS.md
3. MCP tool descriptions (active servers only)
4. Active skill SKILL.md
5. Conversation history + tool call results

Keep items 2-4 as lean as possible to maximise budget for item 5.
RTK reduces item 5 by 60-90%, which is the single highest-ROI optimization.

### Sub-Agent / GSD Model Selection

| Task | Tool | Model |
|---|---|---|
| Orchestration, architecture planning | GSD orchestrator | Opus |
| Codebase research, grep/trace | GSD research agent / sub-agent | Haiku |
| Feature implementation | GSD executor | Sonnet |
| Plan writing | GSD planner | Sonnet |
| Verification / UAT | GSD verifier | Sonnet |
| Code review | Superpowers code-review | Sonnet |
| ADR drafting | Custom sub-agent | Sonnet |

### GSD Command Cheatsheet

| Command | When |
|---|---|
| `/gsd:new-project` | First time on a codebase |
| `/gsd:discuss-phase N` | Start of each phase — lock preferences |
| `/gsd:plan-phase N` | Research + plan + Nyquist gate |
| `/gsd:execute-phase N` | Parallel implementation |
| `/gsd:verify-work N` | Goal-backward UAT before PR |
| `/gsd:ship N` | Creates draft PR |
| `/gsd:quick "task"` | Small tasks without full workflow |
| `/gsd:quick --research "task"` | Quick research before committing |
| `/gsd:progress` | See where you are |
| `/gsd:forensics` | Debug stuck/corrupted runs |
| `/gsd:settings` | Configure model profile, toggles |

### RTK Command Cheatsheet

| Command | Purpose |
|---|---|
| `rtk init -g` | Wire into Claude Code globally |
| `rtk gain` | Show token savings |
| `rtk gain --graph` | ASCII savings graph |
| `rtk git status` | Compressed git status |
| `rtk gh pr list` | Compact PR listing |
| `rtk grep "pattern" .` | Grouped search results |
| `rtk ls .` | Token-optimized directory tree |

---

*"The next time your coding agent isn't performing the way you expect,
before you blame the model — check the harness. It's just a skill issue."*
— HumanLayer

*Extended with spec-driven workflow (GSD), token compression (RTK), and
an auto-activating skills framework (Superpowers) — because a good harness
compounds.*
