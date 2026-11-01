# Icons

Gilune draws Material icons. Two places provide them:

- **The core set**, `org.jetbrains.compose.material:material-icons-core`:
  about fifty everyday icons (Add, Close, Search, Settings, Menu, the
  keyboard arrows, …) in every style. Import them from
  `androidx.compose.material.icons.<style>.<Name>` as usual.
- **This folder**, for everything else the app draws. Each file is one
  icon copied from the extended set, `material-icons-extended` 1.7.3, with
  only its `package` line changed.

The extended set itself is not a dependency, and must not become one: it is
a 36 MB jar of about 2,000 icons, and every package (MSI, DEB, APK) carried
all of it for the few dozen we use. Removing it, with one unused runtime
file, took the Windows installer from 139 MB to 99.6 MB (2026-11-01).
`IconSourcesTest` fails if it comes back.

## Adding an icon

1. Find it at <https://fonts.google.com/icons?icon.set=Material+Icons>
   (Material **Icons**, not Material Symbols). The Compose name is the
   icon's name in PascalCase: `note_add` is `NoteAdd`. Styles are
   `Filled` (Compose's `Default`), `Outlined`, `Rounded`, `Sharp` and
   `TwoTone`.
2. Try the core set first: write
   `import androidx.compose.material.icons.outlined.NoteAdd`. If it
   compiles, use it and stop.
3. Otherwise copy it from the extended set's sources, kept at the version
   above so every copied icon matches:
   <https://repo1.maven.org/maven2/org/jetbrains/compose/material/material-icons-extended-desktop/1.7.3/material-icons-extended-desktop-1.7.3-sources.jar>
   (check it against the `.sha1` beside it). Inside, the icon is
   `commonMain/androidx/compose/material/icons/<style>/<Name>.kt`, in lower
   case for the style. Icons that flip for right-to-left text (arrows,
   undo/redo, list and indent glyphs) live under `automirrored/<style>/`
   and are used as `Icons.AutoMirrored.<Style>.<Name>`.
4. Save it here as `<Name>.kt` and change exactly two things:
   - `package androidx.compose.material.icons.<style>` becomes
     `package org.gilune.app.icons`;
   - under the licence header, add the two lines every file here carries:
     ```kotlin
     // Copied unchanged from material-icons-extended 1.7.3 except for the
     // package line; see README.md in this folder.
     ```
   Keep the Apache 2.0 header as it is. The licence asks for it, and it
   records where the drawing came from.
5. At the call site, import `org.gilune.app.icons.<Name>`. The code that
   draws it does not change: it is still `Icons.Outlined.<Name>`.

If you need two styles of one icon, the second file needs its own name
(`NoteAddFilled.kt`) and its own private cache variable (`_noteAddFilled`),
because both live in this one package.

Removing an icon from the app? Delete its file here too once nothing
imports it.
