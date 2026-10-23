# PLAN.md

One section per milestone. Fill them in order, with the plan before you run
anything, the guardrail before you run a task, and the run after.

Keep it short and specific. Point at modules, gates, and file paths.

---

## Milestone 1: The plan

Turn `CHANGE-REQUEST.md` into three or four agent-sized tasks in the
Supervision Plan format. Every task carries a verification checkpoint, and the
section ends with an out-of-scope list.

**The tasks.** Three or four of them in dependency order. A task is
agent-sized when one agent run can finish it and one gate can tell you whether
it did. For each one, give:

- what behavior it delivers (not "edit module X"), which modules it touches,
  and what it depends on
- its checkpoint, meaning the gate command you run and the observable result
  that would convince you the task is done ("Run the tests" is not a
  checkpoint)
- one specific way the task could go wrong and still leave the gate green

**AGENTS.md rules 3 and 4.** One line: which of your checkpoints catches an
agent that breaks rule 3 (the conflict predicate) or rule 4 (waitlist writes).

**Out of scope, with reasons.** Which modules you are not touching and why. A
reason is a clause in the change request or a rule in AGENTS.md, not "it seemed
unrelated".

### Task 1 — Domain modification behavior and history

Deliver an in-place modification operation in `core-domain`. A request may
replace the interval, attendee count, or both, but never the room. The service
must reject a non-owner, missing/cancelled/started booking, every ordinary
booking-rule violation, a room collision, or an owner-calendar collision while
leaving the original booking unchanged. It must ignore the booking being edited
in both conflict checks and waive only the active-booking-count cap. A successful
interval change runs the existing FIFO waitlist promotion pass through
`BookingService`; attendee-only changes do not. A no-op succeeds without a
history entry or event. Each real change appends an immutable history entry with
the old and new interval and attendee count plus the modification instant, and
publishes a new `BookingModifiedEvent` carrying the same old/new details. This
task may change domain model/result/event types, `BookingService`, and tests in
`core-domain`; it depends on nothing.

Checkpoint: run `mvn -B -pl core-domain -am test`. When the new
tests pass for shorten, lengthen, move, attendee-only, and no-op changes; all
ownership/timing/rule rejections preserve the original booking; self-exclusion
allows a 15-minute nudge while real room and user conflicts are rejected;
interval changes exercise FIFO promotion and its waiter-calendar re-check; and
event publication failure does not undo a modification. The gate could remain
green if history is stored correctly but exposed in a form the later HTTP JSON
view cannot serialize cleanly.

### Task 2 — Owner modification notification

Teach `notifications` to turn `BookingModifiedEvent` into an owner notification
whose subject/body identify the booking and show both old and new interval and
attendee details. Register the new notification kind and shipped template while
preserving fire-and-forget behavior. This task touches only `notifications` and
its new tests and depends on Task 1's event contract.

Checkpoint: run `mvn -B -pl notifications -am test`. When a
notification test publishes a modification event, observes exactly one queued
message containing both complete old and new details, and also proves a missing
recipient or template is recorded as skipped without an exception. The gate
could remain green if the wording is technically complete but too ambiguous for
a person to tell which displayed values are old and which are new.

### Task 3 — Event-log contract and current-state analytics

Extend storage's single `EventLogFormat` writer with `booking-modified`, including
old and new interval/attendee fields, and extend the Python reader's one ledger
fold so a modification replaces the booking's current interval and attendees.
Add storage serialization tests and analytics parsing/folding/utilization tests;
do not special-case the revenue report, which must keep reading the shared
ledger. Update the event-type lists in `AGENTS.md` and `ARCHITECTURE.md` in the
same task as required by AGENTS.md rule 6. This task touches `storage`,
`analytics`, those two convention files, and depends on Task 1.

Checkpoint: run the single combined gate
`mvn -B -pl storage -am test; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }; Push-Location analytics; pytest; $code = $LASTEXITCODE; Pop-Location; exit $code`.
When storage tests assert the exact new JSON line and known-type
list, while Python tests parse it, fold multiple modifications in order, and
show utilization counting only the latest duration and attendee count; all Java
and all 137+ Python tests must pass. The gate could remain green if a hand-edited
event-type statement in either convention file is misspelled because prose is
not executable.

### Task 4 — Owner-only PATCH and history on existing reads

Expose modification as `PATCH /bookings/{bookingId}` in `http-api`. Omitted
interval or attendee form fields retain the booking's current value; the handler
authenticates the owner (administrators may still read history but may not
modify), calls the domain operation, returns the modified booking plus any
promotions, and maps domain rejections through the existing error mapping.
Include modification history in the existing single-booking/list JSON views,
with no new read endpoint, and update the HTTP-surface table in
`ARCHITECTURE.md`. This task touches only `http-api`, its tests, and that table;
it depends on Tasks 1–3.

Checkpoint: run `mvn -B -pl http-api -am test`. When endpoint
tests show an owner can PATCH interval only, attendees only, or both; a
non-owner/admin modification is forbidden; conflicts and invalid changes return
the mapped status without mutation; no-op PATCH produces no history; existing
GET responses show ordered history to owner and administrator; and a freed slot
returns its promotion. The gate could remain green if the tests call handler
methods directly and the real router has not actually registered the PATCH
method, so at least one test must use the HTTP test server end to end.

### AGENTS.md rules 3 and 4

Task 1's checkpoint catches both: its conflict cases must route through
`ConflictChecker`/`TimeInterval.overlaps` (rule 3), and its interval-change tests
must demonstrate that only `BookingService` performs FIFO promotion with both
room and waiter-calendar re-checks (rule 4). During diff review I will also reject
any new start/end comparison or production `WaitlistStore.save/remove` caller.

### Out of scope

- `billing`: the change request says the price is frozen—no repricing,
  re-invoicing, or voiding—so no billing code or tests change.
- `cli`: the change request explicitly says no CLI command is wanted and its
  existing administrative rendering does not require a modification surface.
- `accounts`: the change request says modification reuses the existing token and
  ownership checks, so account management does not change.
- Analytics revenue behavior: the request accepts its possible disagreement
  with the frozen invoice and explicitly forbids filtering or forking the ledger;
  only the shared fold and utilization expectations may change.
- Room-changing behavior: the request fixes the room for a modification, so no
  move-to-another-room API or domain path will be added.

---

## Milestone 2: The guardrail

Pick one recipe from `guardrails/README.md`. Configure it, then prove it binds.

**The recipe.** Which one you picked, the exact paths or trees it covers, and
one line on what it cannot catch.

**The captured block.** Paste the excerpt of the agent's own turn showing what
you asked it to do, its tool call, and the block the guardrail returned. A bare
terminal paste with no agent turn around it does not show the boundary binding.

### Recipe and boundary

I chose Recipe C, filesystem lockdown, adapted to Windows by using an explicit
deny-write ACL for my Windows account. The shipped `lockdown.sh` uses Unix
`chmod`, so I used the operating system's native ACL enforcement instead. The
configured trees in `guardrails/protected-trees.txt` are `accounts`, `billing`,
and `cli`, matching the out-of-scope modules in Milestone 1. Within each tree I
locked `src/` recursively and `pom.xml`, while leaving `target/` writable so the
Maven gates can still run. Before locking, I saved each module's original ACL in
`guardrails/*-acl-before-lock.txt` so it can be restored after the supervised
task.

This guardrail cannot catch an edit in an allowed module that indirectly changes
out-of-scope behavior; the full build and diff audit are still required.

### Captured block

I deliberately targeted `cli/pom.xml`. The edit was rejected by the Windows
filesystem deny-write ACL, and I verified afterward that the file was unchanged.
The active ACL includes `SpooderMan\mihir:(DENY)(W)` on `cli/pom.xml`.
---

## Milestone 3: The run

Run exactly ONE task from your plan with the agent, under the guardrail, through
its gate. Any task whose dependencies are done qualifies, including the
smallest one.

**The takeover point.** Write this before you start the run. A concrete,
conditional statement of when you would stop the agent and write the code
yourself. "If something looks wrong" does not count. Name the trigger and name
what you would do.

**The evidence.** Name the task from Milestone 1. Paste the result line of its
gate command (run it without `-q` so the line is in the output) and the result
line of one `mvn -B clean test` run after the task was finished. The module
gate cannot see a cross-module compile break on a warm tree. A cold build can,
and so can CI.

**The diff.** The files the agent changed, and how each one maps to that task's
declared scope (plain `git diff` leaves out new files, but
`git add -N . && git diff --stat` includes them). Note anything it touched that
you did not expect, including any shipped test. Then quote the one line you
read hardest and say what you were checking for.

**The refuter.** What you gave it and what you asked it to do (paste the
brief). Then the one finding you settled, and how (the new failing test with
its red run, or the evidence that dismissed it). If nothing it reported held
up, say how you checked its strongest claim.
