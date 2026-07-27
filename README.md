# A policy an agent cannot argue with

Working machinery for
[The ground truth, made executable](https://www.juxt.pro/blog/the-ground-truth-made-executable/),
the third post in a series on trustworthy agent loops.
[Post one](https://www.juxt.pro/blog/a-loop-needs-a-ground-truth/) argued that a
loop is only as trustworthy as what it verifies against.
[Post two](https://www.juxt.pro/blog/ai-agents-in-banking-operations/) argued
that banks already hold that ground truth, written down as policy. This
repository is the implementation: a written settlement reconciliation policy
turned into a specification, a deterministic check, and an agent that can call
the check but never argue with it.

One settlement break, run through a real check:

```
./run.sh
```

The only prerequisite for that is Java 21 or later. The Gradle wrapper fetches
everything else on first use.

## What is here

| | |
|---|---|
| `policy/recon-policy.md` | the prose policy, as amended after the derivation run |
| `policy/recon-policy.original.md` | the policy before the run; point the loop here to replay the questions |
| `policy/recon.allium` | the behavioural specification, `allium check` clean |
| `policy/tolerances.v1.yaml` | the machine-legible policy: thresholds and triggers as versioned data |
| `policy/tolerances.v2.yaml` | the same policy after the committee tightened it |
| `data/breaks.json` | the queue, standing in for a reconciliation platform |
| `data/case_history.json` | the resolved-case library the agent reads, never a verdict |
| `agent/recon_agent.py` | the API path, the outer loop written out in a few dozen lines |
| `src/main/kotlin/recon/policy/ReconChecker.kt` | the deterministic check |
| `src/main/kotlin/recon/cli/ToolSchema.kt` | the tool contract, as a model is handed it |
| `src/main/kotlin/recon/audit/AuditLog.kt` | the hash-chained trail |
| `src/test/kotlin/` | the policy as assertions, written before any model got near it |

## The four properties

**The agent brings the case, never the figures.** `check_break` takes a break
identifier and nothing else. There is no field through which a model-produced
number could reach the check; the amounts are read from the break record the
reconciliation platform raised.

**The action is gated, not the prompt.** `propose-closure` re-runs the check
itself. A proposal that contradicts the verdict is refused however the rationale
is phrased. Try it:

```
./recon propose-closure B-1003 --rationale "Counterparty has a long history of clean settlement. Recommend closure."
```

**The agent can add doubt but never remove it.** Escalation is always available
from any verdict. Closure never is.

**Every decision is pinned.** The verdict records the rule that fired, the
threshold it applied and the policy version it ran under. Change
`policy/tolerances.v1.yaml` to `v2` and the same break stops being closeable,
while cases already closed still point at the version that closed them.

## Working it with an agent

Open this directory in [Claude Code](https://claude.com/claude-code) and ask it
to work the queue. `CLAUDE.md` gives it the tools and the rules of engagement; it
gets no special knowledge of which breaks may be closed, because that is the
check's job, not the model's.

With an API key, `agent/recon_agent.py` runs the same case through a plain
tool-use loop you can read in full, including the gate that returns a refused
proposal to the model as a tool error and the guard that pins a session to its
break:

```
pip install anthropic
export ANTHROPIC_API_KEY=...
python3 agent/recon_agent.py B-1002
```

## Tracing a decision

Every check, proposal, refusal, escalation and confirmation lands in a
hash-chained trail, each record carrying the rule that fired, the verdict id and
the policy version it ran under:

```
./recon audit B-1002       # the trail for one case
./recon verify-audit       # recompute the hash chain
```

## Running the parts

```
./gradlew test           # the checker and the audit chain
./recon tools            # the tool contract, as the agent sees it
./recon list             # the queue
./recon show-break B-1001
./recon history B-1001   # the resolved-case library for a break's counterparty
./recon check B-1001
```

Fix the clock for a byte-identical run with `RECON_CLOCK=2026-07-20T09:00:00Z`.
Point at a different policy with `RECON_POLICY=policy/tolerances.v2.yaml`.

The specification tooling is a separate install, only needed if you want to
check or extend the spec:

```
brew install juxt/allium/allium
allium check policy/recon.allium
allium plan policy/recon.allium    # the test obligations the suite discharges
```

## Rebuilding it from the policy

The whole pipeline is replayable. In Claude Code, with the
[Allium plugin](https://github.com/juxt/allium) installed:

```
/allium derive a specification from policy/recon-policy.original.md
```

The original policy is silent on two points, what a USD cap means for a break in
another currency, and whether an escalation trigger outranks the tolerance, so
the loop will stop and ask you both questions rather than guess. Answer them as
the policy owner, and then implement against the result:

```
/allium implement the specification in policy/recon.allium
```

## Extending it

**A new currency** is a config entry in `tolerances.v1.yaml`. Nothing else
changes; a currency without an entry escalates by design.

**A new escalation trigger** is a config entry plus one branch in `holdsFor`
(`ReconChecker.kt`), because config names a condition and code decides what it
means. The specification's closed-vocabulary invariant is where the allowed
conditions are declared, so amend that too, and `./gradlew test` will tell you
what the change owes.

**A policy change beyond the numbers** re-enters the pipeline at the top: amend
the prose, re-derive or tend the specification, and reimplement against it. The
version stamp in the config is what keeps closed cases pinned to the policy they
were closed under.

This is a demonstration, not a product. The break store is a JSON file standing
in for a reconciliation platform, and the analyst queue is a command line. What
is not simplified is the part the posts are about: the policy, the check, the
gate and the trail.
