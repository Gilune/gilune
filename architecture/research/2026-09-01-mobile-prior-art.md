# Mobile prior art for Gilune — live-preview editing, formatting toolbar, capture-first

Research brief for the mobile design agent. Compiled 2026-09-01.

**How to read this.** Every claim is either sourced to a URL that was actually
fetched, or marked *(unverified)*. Where a claim comes only from a search-engine
summary rather than a page I read end to end, it says so. Praise is useless to a
designer; every entry tries to state the *interaction* — what the user touches,
what moves, what breaks.

**Gilune's fixed constraints** (they decide several arguments below):

- The file on disk is always plain Markdown. Rendering may never change bytes.
- Capture must complete with zero network and zero transcription available.
- The desktop app is the family identity: light, quiet, periwinkle accents,
  hairline dividers.
- The editor is Compose Multiplatform, which caps what "rich" can mean inside an
  editable buffer (§7).

---

## 1. Obsidian mobile — the closest cousin

### 1.1 What Live Preview actually does

Obsidian's help is thinner than its reputation suggests. The documented rule is:

> "When your cursor enters formatted content, the underlying syntax becomes
> visible for editing."
> — <https://obsidian.md/help/edit-and-read>

Source mode by contrast "displays all Markdown syntax exactly as written", and
Reading view removes editing entirely. Live Preview is the default editing view.

In practice the granularity is **per line for block-level markers** and **per
element for inline markers**:

- The caret's line shows raw syntax: "the line with the cursor shows us the
  actual Markdown, but everything else in our note has been rendered"
  (<https://thesweetsetup.com/how-live-preview-works-obsidian/>). So `#`, `- `,
  `> ` reveal when you enter the line.
- Inline spans (`**bold**`, `[[wikilink]]`) reveal when the caret enters *that
  span*, not merely that line — this is implied by the help wording ("cursor
  enters formatted content") and by the link-editing behaviour in §1.4, but I
  found no page stating the rule explicitly. *(granularity partly unverified)*
- Selection behaviour — whether selecting across a rendered span reveals it — is
  **not documented anywhere I could find**. *(unverified)*

### 1.2 The reveal-reflow problem — the single biggest hazard

The recurring complaint is not that syntax appears, it is that **the layout moves
when it appears**:

> "when the editor toggles between showing and hiding Markdown syntax, the lines
> shift and the cursor jumps — which makes continuous typing and reading really
> difficult."
> — <https://forum.obsidian.md/t/to-hide-markdown-syntax-in-live-preview-so-it-doesn-t-show-formatting-markers/109155>

The thread has no workaround; a replier asks for Notion/Google-Docs-style
seamlessness instead. Related: Live Preview adds padding/margins around headings
and lists that make the rendered spacing disagree with the file's spacing
(<https://blog.chaoticgood.computer/content/notes/fixing-obsidian-paddings-and-margins>,
via search summary — page not fetched).

Why it matters for Gilune: on a phone the caret is under a thumb, near the
bottom of a 6-inch column. Any reflow at the reveal boundary throws the text the
user is aiming at. This is the design problem to solve first, not last.

### 1.3 Checkboxes and tasks

- Checkboxes render as tappable controls in Live Preview and toggle on tap; 1.11
  "added haptics to toggles and checkboxes"
  (<https://obsidian.md/changelog/2026-01-12-mobile-v1.11.4/>).
- But tapping one has historically stolen the caret. Reported on Android
  (Obsidian 1.7.7): tap a task checkbox and "the cursor jumps to the previous
  position in the note, requiring manual scrolling and refocusing on the task"
  — <https://forum.obsidian.md/t/cursor-jumps-to-previous-location-when-changing-task-status-in-live-preview-on-mobile/96385>
- 1.11 lists a fix in the same family: tapping now places the cursor at the
  tapped location rather than the previous position
  (<https://obsidian.md/changelog/2026-01-12-mobile-v1.11.4/>).

Lesson: a tap on an interactive glyph inside an editable buffer has to answer
*two* questions — toggle the box, and where does the caret go. Getting the second
one wrong is what users report as a bug.

### 1.4 Rendered elements swallow taps (the caret-placement trap)

In Live Preview a single click on a wikilink **follows the link**; it does not
place a caret. To edit the link text you must place the caret at the link's edge
and walk in, or use a modifier chord (Cmd-Opt-click on macOS)
(<https://forum.obsidian.md/t/best-way-to-edit-a-link-in-live-preview-mode/110536>;
also the thread "Add setting to enable simple left click link editing in Live
Preview Mode", <https://forum.obsidian.md/t/add-setting-to-enable-simple-left-click-link-editing-in-live-preview-mode/33687>, seen in search results but not fetched).

**On a phone there are no modifier keys.** No Obsidian documentation I could find
says how to place a caret inside a rendered link on touch. *(unverified — and
the absence is itself the finding)*. Any element Gilune renders as a tap target
inside the editable buffer creates a region the user cannot aim a caret at.

### 1.5 The mobile toolbar

From the official help, <https://obsidian.md/help/mobile> (also cross-checked
against a mirror of the help repo at
<https://huggingface.co/spaces/anpigon/obsidian-qa-bot/blob/f7189e0d855ccced9dc51b88508739e4e83dfc63/docs/obsidian-help/Getting%20started/Use%20the%20mobile%20app.md>):

- "When editing a note, you will notice a row of icons at the bottom of the app."
  It sits **at the bottom during note editing**, i.e. directly above the software
  keyboard, giving "quick access to commonly used functionalities".
- Configured by tapping a wrench icon in the toolbar itself, or
  Settings ▸ Mobile ▸ *Manage toolbar options* (add, remove, reorder).
- Defaults are "editing options like *Add internal link* or *Add tag*"; the help
  gives no exhaustive default list. *(exact default set unverified)*
- Overflow is horizontal: "If you have lots of actions on the toolbar, you can
  swipe left and right on the toolbar to reveal more actions."
- Any global command can be added (e.g. "Change theme"), so the toolbar doubles
  as a command launcher.
- On tablets, 1.11 centre-aligned it (<https://obsidian.md/changelog/2026-01-12-mobile-v1.11.4/>).

Documented complaints:

- **Toolbar life is tied to keyboard life.** "there is no way to hide the
  keyboard without also hiding the toolbar"; the requester wants line-swap
  buttons available with the keyboard down —
  <https://forum.obsidian.md/t/mobile-option-to-show-mobile-toolbar-when-keyboard-is-hidden/89844>
- Emptying the toolbar does not remove it; the navigation toolbar takes its place
  (forum thread title, search result, not fetched). *(unverified)*
- Horizontal swipe overflow hides commands the user never discovers — the
  ecosystem of toolbar plugins (Advanced Toolbar, Commander, Note Toolbar) exists
  mainly to add **multiple rows**, always-visible mode and per-platform item
  visibility, which is a strong signal that one scrolling row is not enough
  (<https://github.com/phibr0/obsidian-advanced-toolbar>,
  <https://thedocumentation.org/obsidian-commander/usage/mobile_toolbar/> — both
  from search results, not fetched). *(unverified in detail)*

### 1.6 Navigation, gestures, pull-to-search

From the same help page and the 1.11 changelog:

- A **navigation bar** appears when not editing: back/forward arrows (greyed when
  unavailable), a centre **plus** icon that creates or switches notes (the quick
  switcher), a tab counter, and a menu icon holding the desktop ribbon's actions.
  The mobile app has no ribbon.
- **Pull-down quick action**: you "define one action that gets invoked by pulling
  down from the top of the app, **similar to how you would pull to refresh on
  social media apps**", defaulting to the command palette and configured at
  Settings ▸ Mobile ▸ *Configure mobile Quick Action*
  (<https://obsidian.md/help/mobile>). That is Obsidian's pull-to-search, and
  note that it deliberately borrows the pull-to-refresh muscle memory rather
  than iOS's pull-to-reveal-search-bar.
- Right sidebar opens by **swiping left** while editing or reading, or by holding
  the triple-dot icon.
- 1.11: "New sidebar navigation for switching sidebar tabs and actions, **aligned
  to the bottom for better ergonomics**"; navigation auto-hides on scroll-down to
  maximise reading space; "when navigation is hidden, swiping down from the top
  of the view triggers the pull down action"; "Added double-tap to switch a note
  from reading mode to editing mode"; toasts moved to the top on phones.

### 1.7 Startup — why capture-first is a separate app shape

Obsidian's mobile startup is the most-documented failure in this space, and it is
the reason Drafts exists.

- Feature request "Mobile, startup: Reduce the time until the user can write"
  (<https://forum.obsidian.md/t/mobile-startup-reduce-the-time-until-the-user-can-write/57450>).
  Asks for a minimal editor first, plugins and vault loaded behind it. User
  reports quoted there: "in my large vault, the app takes 4.8 seconds to start up
  which is unusably slow. This vault has 1406 files, total size is 705 MB";
  "Even with all Community Plugins disabled, I could never get the startup time
  on my Pixel to get below 4 seconds"; "Obsidian takes about 15 seconds to start
  up almost every time"; and, damningly, "Launching is so slow that I don't even
  bother using it for notes on the go. I'm forced to use Google Keep."
  A moderator's answer is not a fix but a plan: "Mobile quick capture is on the
  Roadmap as planned".
- Cause analysis: the **workspace** component (restoring open tabs and plugin
  views) dominates, not file count; one author went from ~30 s to 5 s by
  disabling view-creating plugins, versus 1–2 s on a laptop
  (<https://obsidian.rocks/fixing-slow-startup-on-obsidian-mobile/>). A
  startup-time overlay shipped in 1.7.1 to attribute the cost.
- The capture verdict, from the same site: "waiting 5-15 seconds to capture an
  idea is *not* quick", with the recommendation to use Drafts (iOS) or Fleeting
  Notes / Zettel Notes (Android) *instead of* Obsidian for capture, and let sync
  deliver it (<https://obsidian.rocks/obsidian-mobile-quick-capture/>).
- For calibration, Android vitals calls a cold start "excessive" at **5 s or
  longer**, warm at **2 s**, hot at **1.5 s**; TTID is first frame, TTFD is
  "the time it takes for an app to become interactive"
  (<https://developer.android.com/topic/performance/vitals/launch-time>).
  Obsidian's reported figures sit at or beyond the excessive line — an app can
  be *within Play's tolerance* and still be useless for capture.

### 1.8 Where Obsidian is going (worth knowing, and worth beating)

From <https://obsidian.md/roadmap/>:

- Launched Jan 2026, v1.11: **Mobile widgets** — "Quick actions from the lock
  screen, home screen, and more"; **Siri and Shortcuts integration**; **Mobile UI
  refresh** — "making the interface easier to use on touchscreen devices".
- v1.7 (Oct 2024) shipped **Mobile startup time** work and a mobile **tab
  switcher**.
- Active for v1.13: **iOS Share Sheet** — "save content from other apps without
  opening Obsidian", with configurable vault/folder/template.
- Still only *Planned*: **Background sync on mobile**.

Per-platform detail (via search summary of
<https://www.howtogeek.com/obsidian-11-1-is-a-massive-update-for-desktop-iphone-and-android/>,
not fetched): widgets for new note, open note, search, daily note, open app;
iOS lock-screen and Control Centre placement; Android home-screen widgets, app
shortcuts and a **quick settings tile**. *(unverified in detail)*

Read this as: even the incumbent has concluded that capture belongs **outside the
app's main window** — in widgets, tiles, share sheets and voice assistants — and
that the note app's own cold start will never be fast enough.

---

## 2. Bear and iA Writer — the two other answers to "where do the markers go"

Three premises in the brief turned out to be out of date; recorded here because
they change which app is worth copying.

1. **"Polar Bear markup" is history.** Bear 1 defaulted to a Markdown-like syntax
   called *Polar*, with Markdown opt-in. Bear 2 (2023) is **CommonMark** outright,
   and the FAQ no longer mentions Polar or a "Markdown compatibility mode"
   (<https://bear.app/faq/how-to-use-markdown-in-bear/>;
   <https://blog.bear.app/2020/09/how-to-get-started-with-markdown-in-bear/>).
2. **Bear does not store Markdown files.** "Bear uses a SQLite database"; on macOS
   at `~/Library/Group Containers/9K33E3U3T4.net.shinyfrog.bear/…/database.sqlite`,
   and on iOS "you cannot access the database due to iOS storage policies"
   (<https://bear.app/faq/where-are-bears-notes-located/>). `.md` is an **export
   format**, not the store (<https://bear.app/faq/export-your-notes/>).
3. **iA Writer for Android is discontinued** — 3.1.4 "removed from sale in
   September 2024", remaining versions "unsupported and may become less reliable
   with newer Android versions"
   (<https://ia.net/writer/support/help/writer-classic/ia-writer-legacy-for-android>).
   So iA cannot be used as *Android* prior art; its ideas can, its shipping app
   cannot.

### 2.1 Bear — hide the markers globally

- One global switch, not a caret rule: "You can choose to hide or show Markdown
  style characters by toggling the **Hide Markdown** option in Bear's
  `Settings > General`" (<https://bear.app/faq/how-to-use-markdown-in-bear/>).
  Formatting applies inline with no mode switch — type `**bold**` and it goes
  bold, type `#` and it becomes a heading, no preview toggle
  (<https://apppicked.com/en/blog/bear-markdown-notes-review>).
- **Does Bear reveal markers at the caret?** Bear documents only the binary
  toggle, and community requests ask to see markup while editing despite hiding
  it generally — so probably not, but no first-hand confirmation was reachable.
  *(unverified)*
- **Formatting keyboard, not a toolbar row.** A `B I U` button in the keyboard bar
  opens a "custom new iOS formatting keyboard" giving "all main formatting
  options at once" — text transforms, tables, photos, attachments, scanner,
  sketches, and **up/down arrows to move list items**
  (<https://blog.bear.app/2021/03/the-editor-preview-for-bear-2-0-on-ios-is-here/>;
  <https://bear.app/faq/whats-new-in-bear-2/>;
  <https://bear.app/faq/lists-and-todos-in-bear/>). **Tap-and-hold is overloaded**:
  hold attachments for the scanner, hold photo for multi-photo insert. This is the
  key structural alternative to a scrolling row — *a panel that replaces the
  keyboard*, holding two dozen actions at full touch-target size instead of six
  behind a swipe.
- Todos: created by `-` + space, from the formatting keyboard, or ⌘T. Toggle
  gesture, completed-item styling and hide-completed options are **undocumented**
  on Bear's own page. *(unverified)* Todos are checkable from Apple Watch and sync
  back (<https://bear.app/faq/bear-for-apple-watch-overview>).
- Navigation: three sliding panes on iPhone — Sidebar, Notes List, Editor —
  with **tags as the navigation** (inline `#work` files the note; nested tags act
  like folders; one note can sit in several places).
- **Capture surfaces are the richest of any app here**, and are the best prior art
  for Gilune's capture half (<https://bear.app/faq/how-to-use-widgets-with-bear/>,
  <https://bear.app/faq/ios-app-extension/>,
  <https://bear.app/faq/bear-for-apple-watch-overview>):
  - Home-screen widgets: Single Note, Sidebar, Search, Random Note, **Find Notes**,
    **Create Note**.
  - Lock-screen widgets: new note, **new todo note**, last edited, random, search.
  - Control Centre: New Note, New To-Do Note, New Scan Document Note, Search.
  - Share extension: save as a new note **or append to an existing one via
    `Start of…` / `End of…`**, with auto-tagging and source links.
  - Watch: "create new notes with just your voice, append text to existing notes,
    and check off tasks".
- Right on a phone: typography and spacing treated as a feature (configurable
  font, size, margins, line and paragraph spacing); tag navigation with no lag.
  Complaints: Apple-only; sync behind Pro; CloudKit delays; scattered
  data-loss-after-update reports *(unverified, aggregated reviews)*.

### 2.2 iA Writer — show the markers, style them, never move them

- iA argues the case explicitly and it is the strongest argument against
  Gilune's chosen direction, so it deserves quoting: "Fully hidden, Markdown
  becomes a format. It was designed to work without altering." They endorse
  Gruber's rule — **"Don't hide the formatting characters; just style/color
  them"** — and summarise their editor as: "It doesn't hide the Markdown
  formatting characters, it just styles them"
  (<https://ia.net/topics/markdown-and-the-slow-fade-of-the-formatting-fetish>).
- Concretely: "real italic and bold styling for `_italic_` and `**bold**`
  spans, and… **outdented `#`'s for headings**" (<https://ia.net/writer>) — the
  markers are kept but pushed into the left margin so the text block stays flush.
  Link and list punctuation is **dimmed** so it "doesn't compete as much visually
  with the words"
  (<https://thesweetsetup.com/apps/our-favorite-markdown-writing-app-for-the-iphone/>).
- **Terminology trap:** iA's "Syntax Highlight" colours *parts of speech*
  (adjectives, nouns, adverbs, verbs, conjunctions), not Markdown
  (<https://ia.net/writer/support/basics/settings/settings-iphone>).
- **Focus Mode** dims all but the current Sentence or Paragraph, with an optional
  Typewriter mode that centres the caret without blurring (same URL). On a phone
  this is the cheap way to make a small column feel calm.
- Preview is a **separate mode**, never a hybrid — iA's split is
  *edit-with-visible-syntax* vs *preview*.
- **Keyboard accessory row — seven buttons**: search, cursor-left, cursor-right,
  dismiss keyboard, undo, redo, open-Markdown-pane. "Five of the seven buttons
  exist as replacements for standard iOS text editing functions" — i.e. the row's
  main job is *navigation and undo*, not formatting. The seventh opens a
  **customisable 6×4 grid** from a Markdown/Content/Commands/Punctuation library.
  Behaviours worth stealing: tapping `Bold` "will wrap your cursor in two matching
  sets of `**`"; tapping `Link` puts the caret between square brackets and
  **auto-fills the round brackets from the clipboard**
  (<https://thesweetsetup.com/apps/our-favorite-markdown-writing-app-for-the-iphone/>).
- File model: plain `.txt`/`.md` in the ordinary file system, no database
  (<https://ia.net/writer>) — the same contract as Gilune's vault. Its weakest
  reviews are about that seam: file management on iOS, and iCloud sync
  duplication *(unverified)*.
- Right on a phone: no chrome, and — the decisive property — **markers never move,
  so nothing reflows while typing**. Complaints: no true WYSIWYG for those who
  want it; thin organisation beyond folders; Android abandoned.

### 2.3 The three-way trade, stated plainly

- **Bear hides markers globally.** Calmest reading surface; least honest about
  structure — you cannot see *why* text is bold, so repairing malformed markup
  means flipping a global preference and losing your place. Compounded by not
  owning a file at all.
- **iA Writer shows every marker, dimmed and outdented.** Nothing ever reflows,
  the caret never changes what is on screen, and the buffer is byte-for-byte the
  file — at the cost of a permanent visual tax of `**`, `#`, `[]()` on every
  line, heaviest on a narrow phone column where those characters eat real width.
- **Obsidian hides everything except the caret's line.** Best of both on paper,
  and the origin of the one defect the other two do not have: the line under the
  caret changes width as you move through it, so lines shift and the caret jumps
  (§1.2) — the failure mode a thumb-typed phone editor can least afford.

The synthesis for Gilune is in §8: keep the caret-reveal, but borrow iA's
**no-reflow discipline** so revealing a marker never changes the layout the thumb
is aiming at.

---


## 3. Drafts (iOS) — the capture-first precedent, and Google Keep

### 3.1 Drafts: what "capture-first" actually means as a mechanism

Tagline "Where Text Starts"; the pitch is "write first and think about what to do
with it later" (<https://getdrafts.com/>). The whole app is organised around one
decision — *the app opens into a blank, ready-to-type draft* — and one setting
that stops that decision being annoying.

- **"By default Drafts opens to a new, ready-to-edit draft."** The control is a
  single idle timer, **New Draft After**, which "controls how long Drafts must be
  idle before returning to the app will create a new draft" — 30 seconds to one
  hour, or **Never**. Override by **pinning**: "The new draft creation timeout
  can be temporarily disabled by pinning the current draft using the pin icon
  below the editor."
  (<https://docs.getdrafts.com/docs/editor/new-drafts-and-pinning>)
  *Note:* older "After Last Edit"/"Create New Draft" phrasing from the Drafts 5
  era no longer appears in the docs. *(unverified)*
- Why the timer is the clever part: nipping out to copy a URL and coming back
  must **not** throw away your half-written thought; coming back an hour later
  must **not** dump you in an old note. One number separates the two cases, and
  a pin overrides it for long sessions.
- **Inbox → Archive/Trash, like email.** "Individual drafts are created in the
  'Inbox'. When a draft has been processed, it can be moved to the Archive to
  long term storage or the Trash if it no longer needed." Trash purges at 30
  days. Flag is orthogonal to inbox/archive
  (<https://docs.getdrafts.com/drafts/>). Tags are the real taxonomy, with an
  *untagged* filter (<https://docs.getdrafts.com/docs/drafts/filtering>).
- **Workspaces** are saved filter+display contexts — "macros which apply a set of
  filters and options to the draft list, and app": query, tags, dates, sort, tab,
  action group, even theme (<https://docs.getdrafts.com/docs/drafts/workspaces>).
- **Actions** are the "process later" half; they live in **action groups**, one
  of which is loaded into the action bar at a time
  (<https://docs.getdrafts.com/docs/actions/action-groups>). An action can move
  the draft to archive or trash on success, so capture and disposition are one
  tap.

### 3.2 Drafts' dictation — the most directly transferable thing in this report

Drafts ships **its own dictation UI** rather than deferring to the keyboard mic
(<https://docs.getdrafts.com/docs/editor/dictation>):

- **Two verbs, deliberately distinct.** `File ▸ New with Dictation` captures to a
  **new** draft; `Editor ▸ Dictate` inserts "the dictation result in the current
  draft" — dictate at the cursor. (Gilune already has exactly this split.)
- **Continuous by default:** "Drafts will continue to listen for dictation until
  you tap the create/cancel buttons to end a dictation session." This is the
  headline advantage over system dictation, which times out mid-thought.
- **Optional silence timeout** that "will automatically complete a dictation
  session, saving the results, after no changes are detected in the dictated text
  after some time."
- Pause/resume, and **correction in place before committing** — "for minor
  corrections directly in the dictation interface".
- Reachable from widget, action, script and URL scheme "with full x-callback-url
  support" — dictation is a first-class verb, not a keyboard feature.

### 3.3 Drafts' action bar (keyboard accessory row)

Sits "above the on-screen keyboard (iOS), or below the editor (macOS)"
(<https://docs.getdrafts.com/docs/editor/action-bar>). Structure:

- **Left:** action-group selector.
- **Middle:** that group's actions, horizontally scrollable on overflow.
- **Right:** dismiss-keyboard arrow; **long-press it** to browse and run *any*
  action.
- **Swipe up/down on the row** switches the active group — a second axis, so one
  row can hold many rows' worth of commands without a hidden overflow.
- Keys show a short **label** rather than the action name, so abbreviations fit.
- **Arrow/cursor keys are not hardwired** — they are ordinary actions in an
  Editing group ("actions for editing tasks like moving the cursor"). The
  extensibility seam and the built-in nav keys are the same mechanism. Compare
  Gilune's `SearchOperators` registry: same instinct.

Other capture surfaces: a grid widget (1/8/16 slots) whose cells bind to **New
Draft**, **Dictate**, **Search**, a workspace with a live inbox count, or any
action (<https://docs.getdrafts.com/docs/extensions/widgets>); Apple Watch
capture that opens "directly to dictation" on older watchOS
(<https://docs.getdrafts.com/docs/extensions/applewatch>).

Documented criticism is thin and mostly second-hand: steep learning curve,
subscription resistance at the Drafts 5 transition, Apple-only. *(unverified)*

### 3.4 Google Keep — the low-ceremony baseline

- Google's help names a **"Quick capture" widget** for creating notes from the
  home screen, plus a single-note widget
  (<https://support.google.com/keep/answer/13302793?hl=en-GB>). The familiar row
  of small buttons (text, list, voice, photo, drawing) is universally reported
  but **not documented button-by-button by Google**. *(unverified)*
- **The voice button records, transcribes, and keeps the audio.** Google's own
  post: "click on the microphone icon… and record your message… a new screen will
  pop up with the text of your message **and an audio file**"
  (<https://blog.google/products/g-suite/8-tips-help-you-keep-google-keep/>).
  Both artefacts live in one note — which is precisely ADR 0013's shape.
- Reported limits: recordings cut off after a few minutes without warning; a
  speaking pause can be read as end-of-input; accuracy varies — the retained
  audio is the fallback. *(unverified)*
- Remember the Obsidian user quoted in §1.7: Keep is what people fall back to
  when the "real" app is too slow to open. Keep's whole advantage is that it
  opens.

---

## 4. Block editors as the contrast — refuse with a reason

Notion's mobile app is a **different editor from its desktop one**, and that is
the structural objection, not a matter of taste. Everything is a block
(<https://www.notion.com/help/what-is-a-block>), but on the phone "There are no
`/` commands. And no icons that appear on hover. Instead, all content is created
from the editing toolbar located above your keyboard" — a horizontally scrolled
strip of @-mention, comment, image, delete, indent, colour, duplicate, copy block
link (<https://www.notion.com/help/writing-and-editing-basics>). Selection is the
sore point: that page says "Double tap to select text, and drag your selection to
include content across multiple blocks", while the mobile page lists "Select
multiple blocks" among the things you *cannot* do on mobile and warns that
"There are no columns on mobile. Any column structure that you created on desktop
will be collapsed to a single column"
(<https://www.notion.com/help/notion-for-mobile>) — Notion's own docs disagree
with themselves about what a selection can span, and backspace/merge behaviour at
block boundaries is documented nowhere. Notion has also had to fight its own
architecture for speed: its engineering blog admits the mobile apps began as
"simple wrappers that opened the web app in a WebView" and that users found "the
Android app was hard to use because of how long it took to start up", before a
campaign that made launch "more than twice as quickly"
(<https://www.notion.com/blog/notion-on-android-is-now-more-than-twice-as-fast-to-launch>;
an independent 2020 audit measured 12.6 s to render page content on a Nexus 5 —
<https://3perf.com/blog/notion/> — historical, pre-optimisation). Finally,
Markdown is an *export* and a lossy one: "Callout blocks will be exported as
HTML, as there is no Markdown equivalent"
(<https://www.notion.com/help/export-your-content>). For a plain-text person the
sum is: a document whose structure you cannot select across, whose desktop layout
silently collapses, and whose Markdown is a derived artifact — the exact
inversion of ADR 0012, where the Markdown buffer *is* the document.

**Craft** is the honourable version of the same model: "Every paragraph in Craft
is a **Block**", and a block with nested content beneath it *becomes* a Page, so
hierarchy and content share one primitive; on phone it gives blocks a real
gesture vocabulary — swipe to select multiple blocks, then Group to nest them
(<https://support.craft.do/hc/en-us/articles/360019555537-Documents-Pages-and-Blocks>).
Its advantage over Notion on a phone is native clients and genuine offline
editing rather than a web view *(review-blog consensus, unverified)*. The lesson
Gilune should take from Craft is not the block model but the **gesture
vocabulary**: block-ish operations (indent, move, group) deserve gestures, not
just toolbar buttons.

---

## 5. Mobile editing ergonomics — the numbers and the failure modes

### 5.1 Touch targets and text size

- Android: **at least 48dp** for any clickable element ("about 9mm" physical),
  separated by **8dp or more**
  (<https://support.google.com/accessibility/android/answer/7101858>).
- WCAG 2.2 SC 2.5.8 is the legal floor at **24×24 CSS px** with a spacing
  exception, and explicitly recommends aiming at the stricter 2.5.5 for important
  controls (<https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html>).
- Material's Android list docs give **no fixed row height**; list-item
  `minHeight` is `?attr/minTouchTargetSize` — rows are built up from the touch
  minimum, not from the legacy 56dp/72dp figures, which are Material 1 heritage
  (<https://github.com/material-components/material-components-android/blob/master/docs/components/List.md>).
  *(the 56/72 numbers are unverified for M3)*
- Apple's 44×44 pt minimum could not be verified — every developer.apple.com and
  m3.material.io page returns a JavaScript shell to a fetcher. *(unverified)*
- Body text: 16sp with 1.5 line height is the sane editing floor
  *(indicative, from M3 typography docs via search — unverified)*.

**Consequence for Gilune's checkbox glyph:** a `- [ ]` marker rendered at body
size is far under 48dp. Either the glyph gets an expanded touch region (a
transparent 48dp target around a small glyph, the standard trick) or toggling
must be a gesture on the row rather than a tap on the mark.

### 5.2 Caret placement on dense text

- Android's `Magnifier` exists from **API 28** and is already wired into
  `TextView`/`EditText`. Its documented behaviour is worth copying wholesale:
  vertical position **locked to the centre of the current text line**, horizontal
  follows the finger; auto-dismiss once the finger passes half the magnifier's
  content width beyond the line bound; and — critically — it is **never triggered
  when the font is too large** (descent−ascent exceeds the magnifier content
  height)
  (<https://developer.android.com/develop/ui/views/text-and-emoji/magnifier>).
- **This is a direct collision with live preview.** Rendered H1/H2 lines are
  exactly the "font too large" case, so the platform loupe gives up precisely
  where a styled Markdown editor is hardest to aim at. Either heading type stays
  modest, or heading lines need their own caret affordance.
- Compose: `Modifier.magnifier` is a **no-op below API 28**; magnifier support
  arrived in Compose 1.1 for text fields, 1.2 for `SelectionContainer`
  *(unverified — secondary sources)*.
- iOS: long-press the space bar turns the keyboard into a trackpad for caret
  movement, plus the tap-and-hold loupe *(unverified)*.

### 5.3 The keyboard accessory row

- iOS convention: `inputAccessoryView` at the **standard 44 pt** toolbar height;
  iOS 15+ prefers `UIKeyboardLayoutGuide` over keyboard notifications
  *(unverified — secondary sources)*.
- Android/Compose, verified: `WindowInsets.ime` is the keyboard's bottom space,
  with `imeAnimationSource`/`imeAnimationTarget` for before/after an animation
  (<https://developer.android.com/develop/ui/compose/layouts/insets>). Anchor the
  toolbar with `Modifier.imePadding()` plus `navigationBarsPadding()`, and use
  `imeNestedScroll()` for scroll-driven open/close; all of it requires
  `WindowCompat.setDecorFitsSystemWindows(window, false)`
  (<https://developer.android.com/develop/ui/compose/system/keyboard-animations>).
- **Named jank source:** synchronised IME transitions only arrived in **Android
  11 / API 30** (`WindowInsetsAnimationCompat`); on **API 29** the default is
  unsynchronised, and content "snap[s] into place instead of synchronising with
  the keyboard's animation — behaviour that can be visually jarring". Set
  `adjustResize`, and go edge-to-edge first or IME insets never arrive
  (<https://developer.android.com/develop/ui/views/layout/sw-keyboard>). Compose
  animates all inset types automatically, backported to API 21.

### 5.4 Selection versus styled spans and inline replacements

- No official documentation says selection handles break over styled runs.
  *(unverified)* What *is* documented: `SelectionContainer` makes content
  selectable, `DisableSelection` carves out exceptions, and
  `LinkAnnotation`/`withLink` is the sanctioned way for tappable spans to coexist
  with selection
  (<https://developer.android.com/develop/ui/compose/text/user-interactions>).
- Known community friction: `SelectionContainer` intercepts gestures, so taps on
  children are hard to handle (issuetracker 184950231;
  JetBrains/compose-multiplatform#3872) *(unverified)*. **This is the same
  problem as §1.4 in a different vocabulary:** an inline tap target inside
  selectable text is a *gesture-arbitration* problem, not a rendering one. Every
  interactive glyph Gilune renders must have an explicit answer for tap,
  long-press, and drag-through-during-selection.

### 5.5 Swipe on list items

- No fixed cross-app convention exists — the direction is **configurable** in
  every major app. Gmail: left/right each bound to Archive, Delete, Mark
  read/unread, Move, Snooze or None, with an **Undo in a bottom snackbar**;
  Todoist: Complete, Schedule, Delete, Reminders, Select
  (<https://www.todoist.com/help/articles/how-to-change-your-swipe-actions-D5DQOQz6>
  and 9to5google — *both search snippets, unverified*).
- Compose M3's primitive is `SwipeToDismissBox` (StartToEnd / EndToStart /
  Settled, with a coloured background revealed under the swipe). Practitioner
  gotcha: the dismiss state is remembered, so a re-inserted item is immediately
  re-dismissed — reset to Idle after a delete or an undo *(unverified)*.
- The one real convention: **a destructive swipe must pair with an undo
  snackbar.**

### 5.6 Navigation shape

- Android's guidance: a navigation **bar for 3–5 same-level destinations** at
  compact widths; a drawer holds more than 5 but is "not as ideal as the
  navigation bar" because users must reach the top bar; use a **rail** on large
  screens, never a bottom bar there
  (<https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-and-nav-patterns>).
- **Obsidian's answer is the most instructive**: it does not stack two bars, it
  **swaps the bar by mode** — an editing toolbar at the bottom while editing, a
  navigation bar at the bottom when not (<https://obsidian.md/help/mobile>), and
  1.11 moved sidebar navigation to the bottom "for better ergonomics"
  (<https://obsidian.md/changelog/2026-01-12-mobile-v1.11.4/>).
- Bear uses three sliding panes with tags-as-navigation; Google Keep uses a
  drawer, with a floating pill bottom bar reportedly in testing *(unverified)*.

### 5.7 One-hand reach — with the counter-evidence

- Hoober, n > 1,300 observed users: **49% one-handed, 36% cradled, 15%
  two-thumbed**; "thumbs drive 75% of all phone interactions"; of one-handed
  users two-thirds used the right hand despite ~90% right-handedness; the bottom
  of the screen is easier than the top regardless of device size
  (<https://alistapart.com/article/how-we-hold-our-gadgets/>). Smashing's
  easy/in-between/hard zones follow from it
  (<https://www.smashingmagazine.com/2016/09/the-thumb-zone-designing-for-mobile-users/>);
  the popular "bottom third / middle third / top third" split is secondary-source
  embellishment *(unverified)*.
- **Counter-evidence, and it matters:** NN/g disputes the premise — "the bottom of
  the screen is often not the most easily reachable screen region" and "the middle
  of the screen represents the most easily tappable area"
  (<https://www.nngroup.com/articles/bottom-sheet/>). Their bottom-sheet rules:
  support back-gesture dismissal, include an explicit close button (the grab
  handle "is easy to ignore"), don't stack sheets, keep them transient.
- Net: bottom-anchored is right for *frequent* controls, but the very bottom edge
  competes with the system gesture bar; leave a margin and never put a
  destructive control there.

### 5.8 Dark mode

- Material dark theme, from Google's own codelab: base surface **#121212**; avoid
  pure #FFFFFF text because it "visually 'vibrate[s]'"; elevation as translucent
  white overlays (4% subtle, 12% bottom app bar, 48% strong); desaturate
  primaries; text opacity **87 / 60 / 38%**
  (<https://codelabs.developers.google.com/codelabs/design-material-darktheme>).
  **Pure black is not the Material default.**
- Dynamic colour is Android 12+, wallpaper-derived, opt-in via
  `DynamicColors.applyToActivitiesIfAvailable()`
  (<https://developer.android.com/develop/ui/views/theming/dynamic-colors>);
  Force Dark (Android 10) is skipped if you already inherit `DayNight`
  (<https://developer.android.com/develop/ui/views/theming/darktheme>).
  Note the tension: **dynamic colour would dissolve the periwinkle identity**, so
  it is a deliberate refusal, not an oversight.

### 5.9 Pull-down-to-search

- iOS: `navigationItem.searchController` with `hidesSearchBarWhenScrolling`
  defaulting to true, so the bar shows only at scroll-top *(unverified against
  Apple)*.
- **Android has no equivalent system convention.** Obsidian's configurable
  pull-down Quick Action, explicitly modelled on pull-to-refresh, is the better
  precedent for Gilune than the iOS table-view idiom (§1.6).

---

## 6. Voice-first capture on mobile

### 6.1 What Android actually permits (all verified)

- Android 9 (API 28) is where background capture broke: for idle background apps,
  "Your app cannot access the microphone or camera"
  (<https://developer.android.com/about/versions/pie/android-9.0-changes-all>).
- The sanctioned route is a foreground service: declare
  `android:foregroundServiceType="microphone"`, hold
  `FOREGROUND_SERVICE_MICROPHONE` and `RECORD_AUDIO`, pass
  `FOREGROUND_SERVICE_TYPE_MICROPHONE` to `startForeground()`, and declare the FGS
  type in Play Console
  (<https://developer.android.com/develop/background-work/services/fg-service-types>,
  <https://developer.android.com/about/versions/14/changes/fgs-types-required>).
- **The hard limit:** `RECORD_AUDIO` is *while-in-use*. "You cannot create a
  `microphone` foreground service while your app is in the background and you
  cannot launch a `microphone` foreground service from a `BOOT_COMPLETED`
  receiver." Android 12 blocks background FGS starts generally
  (`ForegroundServiceStartNotAllowedException`); Android 14 re-checks permissions
  at service creation and throws `SecurityException`. Note the trap:
  `checkSelfPermission` **still returns GRANTED in the background** — it lies. The
  logcat tell is `Foreground service started from background can not have
  location/camera/microphone access`
  (<https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start>).
- **Widgets and notifications are explicit exemptions.** The verbatim list
  includes "The service starts by interacting with app widgets", "…by interacting
  with a notification", and "…as a `PendingIntent` that is sent from a different,
  visible app" (same URL). **So a home-screen widget tap can legitimately start a
  microphone foreground service, and once running it survives lock and
  screen-off.** This is the single most important implementation fact in this
  report for Gilune's capture story.
- A **Quick Settings tile** is *not* named in that list; whether `TileService`
  counts as "a system component" is **unverified**. Do not design around it
  without testing.
- The FGS notification is mandatory, at `PRIORITY_LOW` or higher
  (<https://developer.android.com/develop/background-work/services/fgs>,
  <https://developer.android.com/develop/background-work/services/fgs/launch>).
- Lock-screen widgets returned in Android 15 QPR1 **on tablets only**, not phones
  *(unverified)*. Phone lock-screen capture therefore means a home-screen widget
  plus `showWhenLocked`, or an assistant hand-off.

### 6.2 Transcribe-later, honestly

- **Google Recorder** is the model for splitting layers: real-time transcription
  is **on-device**, while summarisation "runs in a secure, isolated environment in
  the cloud" — an explicit contract about which capability is always available and
  which is conditional. Transcripts are searchable by word, phrase **or sound**
  ("music or applause"), returning the exact time, and words are editable in place
  (long-press ▸ Edit word ▸ Save)
  (<https://support.google.com/pixelphone/answer/16267698>).
- **Otter** records locally when offline, uploads on reconnect, then shows a
  **processing percentage** and notifies on completion *(unverified — help centre
  403s)*. The transferable bit is that **upload progress and transcription
  progress are two distinct states**, and the user is always told which one they
  are in.
- Apple Voice Memos: on-device transcription, iOS 18+, iPhone 12 or later
  *(unverified — Apple pages are JS shells)*.

### 6.3 "Audio is the source of truth, transcript is derived"

- No formal UX guidance names this pattern. The de-facto convention in Recorder
  and Voice Memos is that **the recording stays the primary object** in the list
  and the transcript is a *view* opened on it — editable, searchable back to a
  timestamp, never replacing the audio. Word-level editing plus timestamped search
  implies a retained mapping from text back to audio offsets; that mapping, more
  than the text, is what makes the audio feel primary.
- **Gilune inverts this** (ADR 0013: transcript in the capture note body, audio
  linked via `gilune-blob`). That is defensible — the vault is a Markdown
  product, not a recorder — but it imports an obligation: the note must never
  look *empty* while transcription is pending, and the pending state needs
  Otter's honesty (captured → queued → transcribing → done/failed). Google Keep
  already proves users accept text-and-audio in one note
  (<https://blog.google/products/g-suite/8-tips-help-you-keep-google-keep/>).

---

## 7. Compose constraints that decide what "rich" can mean

Two verified facts that bound the mobile editor's design before any visual
decision is made.

- **No Compose text field can host inline composables.** Across all three
  `BasicTextField` overloads (`String`, `TextFieldValue`, `TextFieldState`) there
  is no `inlineContent` parameter; `decorator`/`decorationBox` wrap the field, they
  do not place content *within* the text
  (<https://composables.com/jetpack-compose/androidx.compose.foundation/foundation/composable-functions/BasicTextField/api>).
  Therefore rendered richness inside the editable buffer is limited to **text
  styling plus glyph substitution** — exactly what Gilune's desktop
  `MarkdownEditTransformation` already does for checkboxes. Images, embeds and
  tables **cannot** render inside the editable field; they need a separate read
  view or a placeholder glyph. This is the technical reason to refuse the block
  model rather than merely a philosophical one.
- **The transformation API has moved on.** `outputTransformation` on the
  `TextFieldState` overload removes the manual offset-mapping burden that
  `visualTransformation` imposes, and its results are **not** written back into
  `TextFieldState` — i.e. the "styling can never change bytes" invariant is held
  by the API itself rather than by Gilune's own tests
  (<https://developer.android.com/develop/ui/compose/text/user-input>). Worth
  evaluating against the current implementation, especially since
  `VisualTransformation` has a documented history of IME-interaction bugs
  (wrong caret position after backspace; a fixed bug where
  `OutputTransformation` caused duplicate character insertion on some IMEs)
  *(from Compose release notes via search — unverified in detail)*.

**Test implication:** whichever API is used, the mobile editor needs an IME
matrix, not just unit tests — Gboard, Samsung Keyboard, and at least one
IME with aggressive autocorrect and one with a composing region (any
CJK/transliteration IME), because that is where offset mapping breaks.

---

## 8. What this means for Gilune

1. **Take** Obsidian's caret-reveal rule, but pay iA Writer's no-reflow tax:
   reveal markers without changing the line's layout — reserve the marker's width,
   or dim-and-outdent it as iA does — because "the lines shift and the cursor
   jumps" is the single most-cited live-preview failure and it is far worse under
   a thumb than under a mouse.
2. **Take** Drafts' capture contract wholesale: open into a new, ready-to-type
   note, governed by one **idle timer** ("New Draft After", 30 s to an hour, or
   Never) with a **pin** to override it — that single number is what makes
   capture-first survive the app-switch case that Gilune's Ctrl+N equivalent does
   not cover.
3. **Take** the widget as the real capture surface, because it is also the only
   legal one: an app-widget tap is an explicit while-in-use exemption that may
   start a `microphone` foreground service, whereas nothing Gilune can do from the
   background may open the mic — and note that a Quick Settings tile is *not* a
   documented exemption.
4. **Adapt** Drafts' two dictation verbs, which Gilune already has in embryo
   (editor mic = dictate at caret, top-bar mic = new note from speech), by adding
   the three things it lacks: continuous listening with no arbitrary cut-off, an
   optional silence timeout, and a correct-the-text-before-commit step.
5. **Adapt** the toolbar shape away from Obsidian's one swipeable row toward
   Bear's **formatting panel that replaces the keyboard**, keeping only a short
   always-visible row of the iA essentials (caret left/right, undo, redo, dismiss,
   "more") — one scrolling row hides its own commands, which is why three separate
   plugins exist to add rows to Obsidian's.
6. **Adapt** Obsidian's mode-swapped bottom bar (edit toolbar while editing, nav
   bar when not) rather than stacking both, and keep the toolbar decoupled from
   keyboard visibility — the top-voted Obsidian toolbar complaint is that "there is
   no way to hide the keyboard without also hiding the toolbar".
7. **Refuse** the block model, and refuse it on mechanism, not taste: Notion's own
   docs contradict themselves about whether a selection may span blocks, its
   desktop columns silently collapse on phones, and its Markdown is a lossy export
   — whereas Compose text fields cannot host inline composables anyway, so a
   block editor on Gilune's stack would mean abandoning the single-buffer editor
   that makes "the Markdown buffer is the document" true.
8. **Refuse** any rendered element that becomes a tap target without an answer for
   caret placement: Obsidian's rendered links swallow single taps and are edited
   only via modifier chords that a phone does not have, so the checkbox glyph
   should stay Gilune's *only* inline tap target — with a 48dp expanded touch
   region, a haptic on toggle, and a deterministic caret result, since "the cursor
   jumps to the previous position" is exactly how users report this as a bug.
9. **Refuse** a cold start that exceeds roughly a second to first keystroke:
   Android vitals only calls 5 s "excessive", but the Obsidian evidence is that
   4–5 s already loses the user to Keep, and "waiting 5-15 seconds to capture an
   idea is *not* quick" — measure TTFD to a typable caret, not TTID to first
   frame.
10. **Take** the family identity onto the phone deliberately: keep light, quiet,
    periwinkle and hairline dividers, use Material's #121212-family dark surfaces
    with 87/60/38% text rather than pure black, and **decline dynamic colour**,
    which would repaint the accent from the user's wallpaper and dissolve the one
    thing that makes the mobile app recognisably the same product as the desktop.

---

## Sources

**Fetched directly for this report (§1, §7 and cross-checks):**

- <https://obsidian.md/help/edit-and-read>
- <https://obsidian.md/help/mobile>
- <https://obsidian.md/roadmap/>
- <https://obsidian.md/changelog/2026-01-12-mobile-v1.11.4/>
- <https://huggingface.co/spaces/anpigon/obsidian-qa-bot/blob/f7189e0d855ccced9dc51b88508739e4e83dfc63/docs/obsidian-help/Getting%20started/Use%20the%20mobile%20app.md>
  (mirror of the official help repo)
- <https://forum.obsidian.md/t/to-hide-markdown-syntax-in-live-preview-so-it-doesn-t-show-formatting-markers/109155>
- <https://forum.obsidian.md/t/cursor-jumps-to-previous-location-when-changing-task-status-in-live-preview-on-mobile/96385>
- <https://forum.obsidian.md/t/mobile-option-to-show-mobile-toolbar-when-keyboard-is-hidden/89844>
- <https://forum.obsidian.md/t/mobile-startup-reduce-the-time-until-the-user-can-write/57450>
- <https://forum.obsidian.md/t/best-way-to-edit-a-link-in-live-preview-mode/110536>
- <https://obsidian.rocks/fixing-slow-startup-on-obsidian-mobile/>
- <https://obsidian.rocks/obsidian-mobile-quick-capture/>
- <https://thesweetsetup.com/how-live-preview-works-obsidian/>
- <https://developer.android.com/topic/performance/vitals/launch-time>
- <https://composables.com/jetpack-compose/androidx.compose.foundation/foundation/composable-functions/BasicTextField/api>

**Fetched by the research agents (§2–§6):** Bear —
`bear.app/faq/{how-to-use-markdown-in-bear, where-are-bears-notes-located,
lists-and-todos-in-bear, export-your-notes, how-to-use-widgets-with-bear,
ios-app-extension, bear-for-apple-watch-overview, whats-new-in-bear-2}`,
`blog.bear.app/2020/09/…`, `blog.bear.app/2021/03/…`. iA Writer —
`ia.net/topics/markdown-and-the-slow-fade-of-the-formatting-fetish`,
`ia.net/writer`, `ia.net/writer/support/basics/settings/settings-iphone`,
`ia.net/writer/support/help/writer-classic/ia-writer-legacy-for-android`,
`thesweetsetup.com/apps/our-favorite-markdown-writing-app-for-the-iphone/`,
`apppicked.com/en/blog/bear-markdown-notes-review`. Drafts — `getdrafts.com`,
`docs.getdrafts.com/docs/editor/{new-drafts-and-pinning, dictation, action-bar,
navigation}`, `docs.getdrafts.com/drafts/`,
`docs.getdrafts.com/docs/drafts/{filtering, workspaces}`,
`docs.getdrafts.com/docs/actions/action-groups`,
`docs.getdrafts.com/docs/extensions/{widgets, applewatch}`. Keep —
`support.google.com/keep/answer/13302793`,
`blog.google/products/g-suite/8-tips-help-you-keep-google-keep/`. Notion/Craft —
`notion.com/help/{what-is-a-block, writing-and-editing-basics, notion-for-mobile,
export-your-content}`,
`notion.com/blog/notion-on-android-is-now-more-than-twice-as-fast-to-launch`,
`3perf.com/blog/notion/` (2020, dated),
`support.craft.do/hc/en-us/articles/360019555537-Documents-Pages-and-Blocks`.
Ergonomics/voice — `support.google.com/accessibility/android/answer/7101858`,
`w3.org/WAI/WCAG22/Understanding/target-size-minimum.html`,
`github.com/material-components/material-components-android/…/List.md`,
`alistapart.com/article/how-we-hold-our-gadgets/`,
`smashingmagazine.com/2016/09/the-thumb-zone-designing-for-mobile-users/`,
`nngroup.com/articles/bottom-sheet/`,
`developer.android.com/develop/ui/compose/layouts/insets`,
`developer.android.com/develop/ui/compose/system/keyboard-animations`,
`developer.android.com/develop/ui/views/layout/sw-keyboard`,
`developer.android.com/develop/ui/views/text-and-emoji/magnifier`,
`developer.android.com/develop/ui/compose/text/{user-input, user-interactions}`,
`developer.android.com/design/ui/mobile/guides/layout-and-content/layout-and-nav-patterns`,
`developer.android.com/develop/ui/views/theming/{darktheme, dynamic-colors}`,
`codelabs.developers.google.com/codelabs/design-material-darktheme`,
`developer.android.com/about/versions/pie/android-9.0-changes-all`,
`developer.android.com/develop/background-work/services/fg-service-types`,
`developer.android.com/about/versions/14/changes/fgs-types-required`,
`developer.android.com/develop/background-work/services/fgs{,/launch,/restrictions-bg-start}`,
`support.google.com/pixelphone/answer/16267698`.

**Known gaps.** Every `developer.apple.com`, `support.apple.com` and
`m3.material.io` page returns a JavaScript shell to a fetcher, so all Apple
figures (44 pt targets, Voice Memos requirements) and current M3 list/type
numbers are secondary-source only. `help.otter.ai` returns 403. Bear's community
forum 404s. Unresolved questions worth a follow-up: whether Obsidian reveals
syntax on *selection* as well as caret entry; how a caret is placed inside a
rendered link on touch (undocumented anywhere found); whether Bear's hidden
markers reappear at the caret; and whether a Quick Settings tile counts as a
microphone-FGS exemption.
