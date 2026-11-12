# GitHub Dense Repository Grid Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Display many repositories on screen in compact cards; the user explicitly rejected one repository per row.

**Architecture:** Keep account-scoped paginated repository and lazy release APIs. Render compact repository cards in a CSS grid and display a selected repository's releases in a separate accessible dialog. No Worker or database changes.

**Tech Stack:** Existing single-page HTML/CSS/JavaScript, native dialog, Node test runner and VM behavior tests, Ego browser viewport verification.

## Global Constraints

- User requirement: “是要能展示更多的仓库 不是一个仓库占一行”.
- Use four columns from 1100px, three columns from 700px through 1099px, and two columns below 700px. At 390px and 1280px no horizontal page overflow is allowed.
- Cards must remain compact (target 120–135px high), with title, visibility tag, at most two description lines, update date and view-release action on one footer row. Show the repository name without repeating the selected owner on every card; retain fullName in title/accessibility/action data. All release details remain outside the grid.
- Preserve account selection, saved account tokens, pagination, permission hints, lazy release loading, authenticated same-origin private asset URLs, and proxy handling.
- Never put a PAT in source, fixtures, URLs, localStorage, logs, or screenshots. No backend or D1 changes; do not use tokens supplied in earlier chat messages.
- Default-collapse account/token/proxy editing in a settings section; keep saved-account selection, token-configured state, refresh, and settings access visible.
- Dialog has a visible repository name, close control, loading/error/empty states, scrollable contents and long wrapping filenames. Escape and closing return focus; account changes close it and stale responses cannot reopen or overwrite another repository's dialog.
- Do not fetch every repository's releases. Fetch only the selected repository; retained per-account release cache may be reused.

---

### Task 1: Compact repository grid and lazy release dialog

**Files:**
- Modify: `pages/store/index.html` (GitHub HTML, CSS, state and event handlers only).
- Modify: `pages/store/github-flow.test.cjs` as required to accommodate real flow.
- Create: `pages/store/github-dialog.test.cjs` for dialog behavior.

**Interfaces:**
- Consumes existing `githubApi(path, options)`, repository metadata, per-account `ghReleaseCache`, `ghRequestId`, existing account selection and download handlers.
- Produces the same repository-card `data-gh-repo` actions; those actions open a dialog rather than expanding a card. Existing `data-gh-url` buttons continue to download through the existing handler.

- [ ] Step 1: Add failing behavior tests for opening a selected repository only, cached reopen, close while loading, selecting a different repository while loading, changing accounts while loading, and private asset URLs. Assert observable dialog name/content/open state, rendered cards without inline releases, and expected API requests. Follow the existing VM pattern; stub only DOM/browser boundaries and API responses.

```js
// Observable contract used by tests and browser verification:
// clicking [data-gh-repo="alice/app"] opens a dialog named for alice/app;
// only releases?account=alice&repo=alice%2Fapp is requested;
// closing it invalidates its pending request;
// switching to another account invalidates both list and dialog work;
// a private APK download retains /store/api/github/asset on this origin.
```

- [ ] Step 2: Run `node --test pages/store/*.test.cjs` and record the expected failures for missing dialog behavior before implementation.
- [ ] Step 3: Implement responsive grid and card markup. Use minmax(0,1fr), bounded/wrapping text, and consistent card height. Move release rendering into a selected-repository dialog and add a request generation guard independent of list pagination. Close the dialog before switching accounts and when leaving GitHub. Use native dialog semantics and restore focus on close. Keep settings collapsed by default with the account selector available outside it.

```css
#ghList { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:12px; }
@media(max-width:1099px) { #ghList { grid-template-columns:repeat(3,minmax(0,1fr)); } }
@media(max-width:699px) { #ghList { grid-template-columns:repeat(2,minmax(0,1fr)); gap:10px; } }
```

- [ ] Step 4: Run the Store test suite again and ensure previous pagination, account-change and permission-hint cases pass. Manually inspect source for escaped repository/asset strings and absence of private data in public/shared storage.
- [ ] Step 5: Self-review and commit only Store source/tests and this plan locally. Do not push. Write a report with red/green commands, counts, commit IDs, and browser checks still needed.

### Integration verification (parent coordinator)

- [ ] Independently test the latest changes, including 20+ fixture repositories, exact 4/3/2 column counts at 1280/900/390px, card height, no horizontal overflow, dialog close/error/stale responses, account selection, pagination and private download URL handling.
- [ ] Build Worker static assets and obtain code-quality plus acceptance reviews before publishing.
- [ ] Push Store first, update parent submodule pointer, push parent for the existing automatic deployment workflow, and observe successful production deployment.
- [ ] Check the deployed GitHub page's actual card columns and dialog behavior with the existing browser login. Capture an accurate new screenshot for the user; do not present a static mock as the deployed result.
