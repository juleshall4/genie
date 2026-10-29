# Domain documentation

Use a single-context layout:

- `CONTEXT.md` at the repository root contains domain terms and concepts.
- `docs/adr/` contains architecture decision records (ADRs).

## Before exploring

Read `CONTEXT.md` and any ADRs relevant to the work.

If these files do not exist, proceed without flagging their absence
or suggesting that they be created upfront. The domain-modeling skill
creates them when terms or decisions are resolved.

## Use consistent vocabulary

Use the terms defined in `CONTEXT.md` in issues, proposals, code,
and tests. Avoid synonyms that the glossary explicitly excludes.

If a needed concept is missing, reconsider the terminology or note
the gap for the domain-modeling skill.

## Identify decision conflicts

If a proposal contradicts an ADR, identify the record and explain
why the decision should be reconsidered.
