---
name: implement-spec
description: "Implement the result of /to-spec and /to-tickets in code."
disable-model-invocation: true
---

You have been provided a spec. This spec should have tickets associated with it, describing how to implement the spec.

The issue tracker should have been provided to you. If not, tell the user to run `/setup-matt-pocock-skills`.

The goal is the entire spec implemented on a single **integration branch**, with every ticket resolved the way the issue tracker closes work.

The tickets are not a list of steps. They are a **task graph** with blocking relationships between them. This means there is always a **frontier** of tickets which are ready to be grabbed.

Communication to and from subagents should be sparse. Communicate primarily through **context pointers**: to the spec, tickets, research notes, and previous commits. Don't duplicate information already available via pointers.

**Implementer subagents** should be run in the background where possible for maximum concurrency.

The **route** in this repo is [`site/development/workflows/epic-werkwijze.md`](../../../site/development/workflows/epic-werkwijze.md): the CI gate per ticket, the review route per `risico:*` label, task size, models, the notes directory and hand-overs. Read it before step 1; every subagent brief points to it.

## Steps

1. Read the spec, the tickets and the route to understand the task graph. Identify the **shared foundation** ticket and the **pattern ticket**; the foundation and the pattern ticket are merged before the rest of their series fans out.

2. (optional) Use an **exploration subagent** to conduct any exploration required by the tickets - relevant codebase files or external documentation. Ensure the exploration subagent can save files - it should save its markdown notes in a directory outside the repo, accessible by all future subagents. This lets **implementer subagents** focus on implementation rather than exploration.

3. Create the integration branch. If the issue tracker closes work through PRs, or the user asks for one, open a draft PR after the first merge in step 5 (a branch with no commits ahead of main can't open one), marked as closing the spec and tickets.

4. Use **implementer subagents** to implement each ticket, each in its own worktree on its own branch. Each implementer subagent:
   - confirms its worktree is based on the integration branch before starting, and resets onto it if not;
   - calls the Skill tool with `tdd` to build the ticket;
   - pushes and opens a draft PR to the integration branch, so CI is the gate from the first push;
   - merges the integration branch tip into its own branch just before its own merge.

5. Review each ticket along the route for its `risico:*` label, with a fresh fixer per round. Once review and the ticket PR's CI are green, merge it to the integration branch with a **merger subagent**. After the pattern ticket, write its review rules into the epic's rules file and point the remaining tickets at it.

6. If this changes the **frontier** of available tickets, kick off more **implementer subagents** to work on the new tickets. This allows for maximum concurrency.

7. Once all tickets are complete, including the bundled docs ticket, call the Skill tool with `code-review` on the integration branch. Fix all issues raised by the code review in a single **implementer subagent**.

8. If a draft PR exists, mark it ready for review. Otherwise, resolve each ticket the way the issue tracker closes work, and report the integration branch.

9. Clean up all **implementer subagent** worktrees.
