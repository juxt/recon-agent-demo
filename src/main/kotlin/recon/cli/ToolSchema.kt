package recon.cli

/**
 * The tool contract, in the shape a model is handed it.
 *
 * Two things are worth noticing. The input to check_break is a break identifier
 * and nothing else, so there is no field through which a model-produced figure
 * could reach the check. And propose_closure takes a rationale but no verdict:
 * it re-runs the check itself rather than trusting anything it is told.
 */
const val TOOL_SCHEMA = """
{
  "tools": [
    {
      "name": "show_break",
      "description": "Return the break as the agent may see it: a projection of the record with the difference, currency, counterparty, when it was raised and the free-text note. It does not return the raw amounts behind the difference, nor the trigger inputs the check evaluates.",
      "input_schema": {
        "type": "object",
        "properties": {
          "break_id": { "type": "string" }
        },
        "required": ["break_id"],
        "additionalProperties": false
      }
    },
    {
      "name": "find_similar_cases",
      "description": "Return past resolved cases for this break's counterparty, from the resolved-case library. Context for the narrative only: nothing it returns can move a verdict, and the check never reads it.",
      "input_schema": {
        "type": "object",
        "properties": {
          "break_id": { "type": "string" }
        },
        "required": ["break_id"],
        "additionalProperties": false
      }
    },
    {
      "name": "check_break",
      "description": "Run the bank's settlement break policy against a break. Returns a structured verdict: WITHIN_BAND, BREACH or ESCALATE_REQUIRED, with the rule that fired, the threshold applied and the policy version it ran under.",
      "input_schema": {
        "type": "object",
        "properties": {
          "break_id": { "type": "string", "description": "Identifier of the break as raised by the reconciliation platform." }
        },
        "required": ["break_id"],
        "additionalProperties": false
      }
    },
    {
      "name": "propose_closure",
      "description": "Propose closing a break, for an analyst to confirm. The action re-runs the policy check and is refused unless the verdict permits agent-proposed closure. The rationale is recorded but never overrides the check.",
      "input_schema": {
        "type": "object",
        "properties": {
          "break_id": { "type": "string" },
          "rationale": { "type": "string", "description": "Evidence-backed narrative. Every claim should name its source." }
        },
        "required": ["break_id", "rationale"],
        "additionalProperties": false
      }
    },
    {
      "name": "escalate",
      "description": "Hand the case to a person with everything gathered and no recommendation attached. Always permitted.",
      "input_schema": {
        "type": "object",
        "properties": {
          "break_id": { "type": "string" },
          "findings": { "type": "string", "description": "What was found. State facts and sources, not a verdict." }
        },
        "required": ["break_id", "findings"],
        "additionalProperties": false
      }
    }
  ]
}
"""
