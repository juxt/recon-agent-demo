#!/usr/bin/env python3
"""
The API path: a settlement-break agent as a plain tool-use loop.

The subscription path lives in AGENTS.md — open this directory in a coding agent
and ask it to work the queue. This script is the same idea through the Claude API, so
you can see the mechanics with nothing hidden: the tool contract the model is
handed, the loop that runs its tool calls, and the gate that the deterministic
checker enforces regardless of what the model decides.

The five tools shell out to the same `./recon` CLI the checker owns. The model
never touches the policy, the amounts, or the audit trail. It reads the break
and the counterparty's history, calls check_break, and then either proposes a
closure the check permits or escalates. A proposal the check refuses comes back
as a tool error, in the model's context, exactly as it would for a human who
tried to close a breach.

Usage:
    export ANTHROPIC_API_KEY=...        # a key from console.anthropic.com
    python3 agent/recon_agent.py B-1002

Requires: pip install anthropic ; and `./recon` built (the script builds it).
"""

import json
import subprocess
import sys
from pathlib import Path

import anthropic

REPO = Path(__file__).resolve().parent.parent
RECON = REPO / "recon"
MODEL = "claude-opus-4-8"

SYSTEM = """You are a reconciliation analyst's assistant, working a queue of \
settlement breaks in a bank's operations department.

For the break you are given: read it, run the policy check, investigate the \
context around it, and then either propose a closure or escalate to a person. \
Every claim in your rationale or findings must name its source — the break \
record, the verdict, the policy.

You do not decide whether a break may be closed. The policy decides that and the \
check enforces it. If you propose a closure the check does not permit, the tool \
will refuse and tell you why; escalate instead. You may always escalate, and \
when you do, attach what you found and no recommended disposition."""


def recon(*args: str) -> subprocess.CompletedProcess:
    """Run the bank's CLI. Its exit code and output are what the tool returns."""
    return subprocess.run(
        [str(RECON), *args], capture_output=True, text=True, cwd=REPO
    )


TOOLS = [
    {
        "name": "check_break",
        "description": (
            "Run the bank's settlement-break policy against a break. Returns a "
            "structured verdict (WITHIN_BAND, BREACH or ESCALATE_REQUIRED) with "
            "the rule that fired, the threshold applied and the policy version."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "break_id": {"type": "string", "description": "e.g. B-1002"}
            },
            "required": ["break_id"],
            "additionalProperties": False,
        },
    },
    {
        "name": "show_break",
        "description": (
            "Return the break as the agent may see it: a projection with the "
            "difference, currency, counterparty, when it was raised and the note. "
            "Not the raw amounts behind the difference, nor the trigger inputs."
        ),
        "input_schema": {
            "type": "object",
            "properties": {"break_id": {"type": "string"}},
            "required": ["break_id"],
            "additionalProperties": False,
        },
    },
    {
        "name": "find_similar_cases",
        "description": (
            "Return past resolved cases for this break's counterparty, from the "
            "resolved-case library. Context for the narrative only: nothing it "
            "returns can move a verdict, and the check never reads it."
        ),
        "input_schema": {
            "type": "object",
            "properties": {"break_id": {"type": "string"}},
            "required": ["break_id"],
            "additionalProperties": False,
        },
    },
    {
        "name": "propose_closure",
        "description": (
            "Propose closing a break for an analyst to confirm. The action re-runs "
            "the policy check and is refused unless the verdict permits closure. "
            "The rationale is recorded but never overrides the check."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "break_id": {"type": "string"},
                "rationale": {
                    "type": "string",
                    "description": "Evidence-backed narrative; every claim names its source.",
                },
            },
            "required": ["break_id", "rationale"],
            "additionalProperties": False,
        },
    },
    {
        "name": "escalate",
        "description": (
            "Hand the case to a person with everything gathered and no "
            "recommendation attached. Always permitted."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "break_id": {"type": "string"},
                "findings": {
                    "type": "string",
                    "description": "Facts and sources, not a verdict.",
                },
            },
            "required": ["break_id", "findings"],
            "additionalProperties": False,
        },
    },
]


def run_tool(name: str, args: dict) -> tuple[str, bool]:
    """Map a tool call onto the CLI. Returns (text_for_the_model, is_error)."""
    if name == "check_break":
        p = recon("check", args["break_id"])
        return p.stdout or p.stderr, False
    if name == "show_break":
        p = recon("show-break", args["break_id"])
        return p.stdout or p.stderr, False
    if name == "find_similar_cases":
        p = recon("history", args["break_id"])
        return p.stdout or p.stderr, False
    if name == "propose_closure":
        p = recon("propose-closure", args["break_id"], "--rationale", args["rationale"])
        refused = p.returncode != 0
        return (p.stdout or p.stderr), refused
    if name == "escalate":
        p = recon("escalate", args["break_id"], "--findings", args["findings"])
        return p.stdout or p.stderr, p.returncode != 0
    return f"unknown tool: {name}", True


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit("usage: recon_agent.py <break-id>   e.g. recon_agent.py B-1002")
    break_id = sys.argv[1]

    # Build the CLI once up front so the first tool call is not the build.
    subprocess.run([str(RECON), "help"], capture_output=True, cwd=REPO)

    client = anthropic.Anthropic()
    messages: list[dict] = [
        {"role": "user", "content": f"Work break {break_id}."}
    ]

    while True:
        response = client.messages.create(
            model=MODEL,
            max_tokens=4096,
            system=SYSTEM,
            tools=TOOLS,
            messages=messages,
        )

        for block in response.content:
            if block.type == "text" and block.text.strip():
                print(f"\n\033[1magent:\033[0m {block.text.strip()}")
            elif block.type == "tool_use":
                print(f"\n\033[2m→ {block.name}({json.dumps(block.input)})\033[0m")

        if response.stop_reason != "tool_use":
            break

        messages.append({"role": "assistant", "content": response.content})

        results = []
        for block in response.content:
            if block.type == "tool_use":
                # The session is pinned to its break. A call naming any other id
                # is refused before it runs, and the refusal returns to the model
                # as a tool error like any other.
                called = block.input.get("break_id")
                if called != break_id:
                    out, is_error = (
                        f"REFUSED. This session works break {break_id}; "
                        f"the call named '{called}'.",
                        True,
                    )
                else:
                    out, is_error = run_tool(block.name, block.input)
                print(f"\033[2m  {out.strip()}\033[0m")
                results.append(
                    {
                        "type": "tool_result",
                        "tool_use_id": block.id,
                        "content": out,
                        "is_error": is_error,
                    }
                )
        messages.append({"role": "user", "content": results})


if __name__ == "__main__":
    main()
