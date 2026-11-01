---
name: check-docs
description: Validate the repo's markdown after any documentation edit (broken links and anchors, references to merged-away doc names, docs/README.md map completeness, duplicated lines). Use after editing docs/*.md, README.md, AGENTS.md, CLAUDE.md, a module README or a skill, or when asked to clean or check docs, "kiểm tra tài liệu".
---

# check-docs

```bash
node scripts/check-docs.mjs
```

It exits non-zero on **FAIL** and prints **WARN** for a line that appears in two files.

| Output | Fix |
|---|---|
| `link to missing file` | the target was renamed or deleted; fix the link, don't recreate the target |
| `link to missing anchor` | a heading was renamed. Anchors are GitHub slugs (lower-case, punctuation dropped, spaces → `-`); grep for the old `#anchor` in all `.md` and update every link |
| `mentions a document name that was merged away` | use the current name (map and old→new table in `docs/README.md`); the old names are allowed only inside its "Old names" table |
| `does not link to docs/X.md` | a new doc must be added to the "Document map" in `docs/README.md` |
| `WARN same line in A, B` | a fact is stated twice: keep it in the file the map names as its single source and replace the other copy with a link |

## Rules that keep the docs clean (`docs/README.md` has the map)
- State a fact once, link elsewhere. Claims policy → `docs/PROJECT.md` §5; limitations → `docs/DECISIONS.md#known-limitations`; module status → `docs/STATUS.md`; agent rules → `AGENTS.md`.
- Don't create numbered file names; don't create a new doc if an existing one owns the topic.
- Keep `docs/API-CONTRACTS.md` section numbers (§1–§7): code comments cite them.
- Never delete the DRAFT labels in `API-CONTRACTS.md` / `DATA-MODEL.md` without a recorded decision (skill `record-decision`).
- Reference docs are English; README, AGENTS, STATUS, TODO are Vietnamese.

Run it again until it prints `OK`. If you changed code comments that cite a doc, grep `docs/` references too (`grep -rn "docs/" --include=*.java --include=*.yml --include=*.sql`).
