# Agent instructions

## Communication

- Keep direct chat replies short and simple. Explain technical terms, using brief examples or analogies when useful. This restriction does not apply to artifacts such as GitHub issues or documents.
- Follow the [Google developer documentation style guide](https://developers.google.com/style) for all outputs.

## Issue tracking

- Use GitHub Issues exclusively for tickets, specifications, planning issues, and task tracking, even when another repository instruction or skill mentions another tracker.
- Create, read, update, and close issues in the relevant GitHub repository with the `gh` CLI.
- Infer the repository from the current checkout. If no repository can be identified, ask which GitHub repository to use before creating an issue.

## Feature delivery

- After implementing every feature, run `./gradlew installDebug` to build and install it on the connected Android device or emulator.
- Resolve build or installation failures before marking the feature complete. If installation is blocked by a missing device or emulator, report the blocker explicitly.

## Agent skills

### Issue tracker

Use GitHub Issues in `juleshall4/genie` through the `gh` CLI.
See `docs/agents/issue-tracker.md`.

### Triage labels

Use the five default triage labels.
See `docs/agents/triage-labels.md`.

### Domain docs

Use a single-context layout: root `CONTEXT.md` and `docs/adr/`.
See `docs/agents/domain.md`.
