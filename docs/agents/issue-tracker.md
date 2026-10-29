# Issue tracker: GitHub

Track tickets, specifications, plans, and tasks in GitHub Issues
in `juleshall4/genie`. Use the `gh` CLI for all operations.

## Issue operations

Run these commands from the repository checkout:

- Create: `gh issue create --title "..." --body-file /path/to/body.md`
- Read: `gh issue view <number> --comments`
- List: `gh issue list --state open`
- Comment: `gh issue comment <number> --body-file /path/to/comment.md`
- Add a label: `gh issue edit <number> --add-label "..."`
- Remove a label: `gh issue edit <number> --remove-label "..."`
- Assign: `gh issue edit <number> --add-assignee @me`
- Close: `gh issue close <number> --comment "..."`

Use Markdown files for multiline bodies and comments.
Fetch labels when reading issues for triage.

## Pull requests as a triage surface

**PRs as a request surface: no.**

Feature requests and task specifications belong in issues.

## Skill instructions

When a skill says to publish to the issue tracker, create a GitHub issue.
When a skill says to fetch a ticket, read the issue and its comments.

## Wayfinding

Use an issue labeled `wayfinder:map` as the planning map.
Create child issues with `wayfinder:<type>` labels, where the type
is `research`, `prototype`, `grilling`, or `task`.

Link children using GitHub sub-issues. If unavailable, use a task list
in the map and a `Part of #<map>` reference in each child.

Record blockers using native GitHub issue dependencies. If unavailable,
add `Blocked by: #<number>` references to the child issue.

Select the first open child in map order that has no open blockers
and no assignee. Claim it by assigning it to yourself.

When work is resolved, comment on and close the child issue.
Add the result and issue link to the map's decisions.
