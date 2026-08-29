# ADR 0006: Mobile Plugin and UI Profile

Status: **Accepted for v0**  
Validation: Spikes S3 and S5

## Context

A desktop plugin system can load sandboxed packages and create independent
windows. Mobile applications operate under stricter execution, background, UI,
review, and code-distribution rules. Treating mobile as a smaller desktop would
make the plugin promise misleading and threaten store distribution.

Google Play restricts downloading executable code outside its update mechanism,
with constrained treatment of interpreted/VM code:
<https://support.google.com/googleplay/android-developer/answer/16559646>.
Apple's current guidelines generally prohibit downloaded code that introduces or
changes app functionality and define additional rules for hosted plug-ins and
mini apps: <https://developer.apple.com/app-store/review/guidelines/>.

## Decision

- Mobile-store builds execute feature plugins that were bundled with the reviewed
  application package.
- The same plugin contracts may be implemented by dynamically installable
  sandboxed components on desktop/server.
- Remote mobile extensions are limited initially to non-executable data,
  settings, schemas, and declarative workflows/UI that the reviewed host safely
  interprets.
- Third-party UI contributions use shell-rendered declarative slots rather than
  arbitrary native, Compose, WebView, or component-rendered widgets.
- First-party plugins use the same slots when sufficient; missing first-party
  needs drive deliberate slot additions.
- Non-store/open Android distributions may later enable broader plugin execution
  as a clearly separate build profile without changing stored data contracts.

## Alternatives considered

### Downloadable native mobile plugins

Rejected for store builds because of policy, signing, ABI, security, and process-
lifecycle constraints.

### Arbitrary HTML/JavaScript plugin views

Flexible but greatly expands bridge, content-security, accessibility, review,
phishing, and policy risk. A tightly constrained web contribution could be
reconsidered through a later ADR.

### No mobile plugin model

Simpler, but would let first-party mobile features bypass contracts and cause the
desktop and mobile data models to diverge.

### Fully custom declarative UI language

Potentially powerful but becomes a second UI framework. V0 uses a small set of
standard commands, cards, queries, forms, badges, and navigation destinations.

## Consequences

- Mobile users may need an app release to receive new executable plugin logic.
- “Available plugin” depends on platform and distribution profile.
- The open-Android fallback now has a precondition: Google's Android
  developer verification (piloting from late 2026, expanding globally
  through 2027) requires verified-developer registration for installs on
  certified devices even outside Play. See the dated policy matrix in
  `../../spikes/s5-distribution-policy/README.md`.
- Plugin manifests distinguish logical compatibility from installability.
- The shell owns accessibility, themes, navigation, permission prompts, and safe
  rendering.
- Marketplace UX must not imply that every desktop plugin can execute on mobile.

## Validation

S3 proves contract parity and declarative rendering. S5 records a policy matrix
for Google Play, Apple App Store, direct desktop distribution, and open Android
distribution, including what is bundled, downloadable, interpreted, or data-only.

