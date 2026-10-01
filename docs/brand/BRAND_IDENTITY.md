# EduCore Brand Identity

Version 1.0, October 2026. Owner: EduCore maintainers. Implementation tokens live in `docs/brand/tokens.css`; logo files live in `frontend/public/brand/`.

This document replaces the previous indigo-and-grey interface style entirely. Nothing from the old look is carried forward.

---

## 1. Positioning

**What EduCore is.** EduCore is a registry: an institution's system of record for students, courses, enrolments, access rules and the automated jobs that keep those records current. It sits behind a login, it is used daily by a small number of staff, and it is judged by whether its records are correct and whether a change can be made quickly and without doubt.

**Who it is for.**

- *Registrars and administrators* who create and correct student and course records, assign network access and review import jobs.
- *Staff users* with read-mostly access who look up students and their enrolments.
- *Students* (via the profile view) who see and select their term courses.
- *Operators* who deploy and observe the platform.

**What it is not.** EduCore is not a learning platform, a marketing product or a dashboard for executives. It never needs to persuade. It needs to be legible, consistent and quiet.

**Positioning statement.** EduCore is the calm system of record for a teaching institution: every student, every course, every rule, in one place, verifiable at a glance.

**Promise in one line.** *Records you can trust.* Turkish: *Güvenebileceğiniz kayıtlar.*

---

## 2. Name usage

- The product name is **EduCore**: one word, capital E, capital C, no space, no hyphen. Never "Educore", "EDUCORE", "Edu Core", "edu-core" or "EduCore System".
- In running text, write the name in the same weight and typeface as the surrounding text. Do not italicise, bold or colour it.
- In Turkish, inflect with an apostrophe: *EduCore'a*, *EduCore'da*, *EduCore'un*.
- Do not append descriptors in UI titles. The window title is `EduCore` or `Students · EduCore`, never "EduCore Dashboard System".
- The internal artefact name `project3` must never appear in anything a user sees.
- Code identifiers may use `educore` in lowercase (package names, env vars, CSS prefixes). That is the only lowercase form.

---

## 3. Brand personality

Four traits, each with the behaviour it produces in the interface.

| Trait | Means | In the interface |
|---|---|---|
| **Exact** | Every value shown is the value stored. | Tabular numerals, monospace identifiers, no rounding without a label, no decorative numbers. |
| **Calm** | Nothing competes for attention that does not deserve it. | One accent colour, hairline structure, no gradients, no glow, motion only as feedback. |
| **Institutional** | It behaves like a registrar's office, not a start-up. | Sentence case, full words, consistent terminology, nothing playful in system copy. |
| **Accountable** | Every change is attributable and reversible where possible. | Explicit confirmation copy, visible soft-delete state, job logs treated as first-class. |

What EduCore is never: enthusiastic, cute, mysterious, decorative, urgent.

---

## 4. Logo

### 4.1 Concept: the Ledger Mark

The mark is three horizontal rows inside a rounded square. The middle row is shorter and is completed by a detached solid square, the **core**. Read together, the rows are the lines of a register; the detached square is the single record that is currently in focus. The glyph echoes the horizontal bars of the capital *E* in the wordmark without spelling a letter, so it works as an abstract institutional seal at any size.

Construction (48-unit grid):

- Tile: 48 × 48, corner radius 12 (25%).
- Rows: height 5, corner radius 1.5, left edge at 13, right edge at 35.
- Row 1: y 13.5. Row 2: y 21.5, width 13 (x 13 to 26). Core: 5 × 5 at x 30, y 21.5. Row 3: y 29.5.
- Glyph bounding box 22 × 21, optically centred in the tile.

### 4.2 Wordmark

"EduCore" is drawn as a monoline geometric wordmark (stroke 4.4 on a 26-unit cap height, round caps and joins). It is a drawn asset, not live text, so it renders identically everywhere. The *E*'s three bars sit on the same rhythm as the mark's rows.

### 4.3 Files

| File | Use |
|---|---|
| `frontend/public/brand/logo-mark.svg` | Tile mark, brand petrol tile with paper glyph. Works on light and dark surfaces. |
| `frontend/public/brand/logo-mark-mono.svg` | Glyph only, `currentColor`. For the sidebar, print, single-colour contexts. Inline it in markup (or use it as a CSS `mask-image`); an `<img>` tag cannot pass colour through, so the glyph would render black. |
| `frontend/public/brand/logo-full.svg` | Mark + wordmark, ink wordmark. For light surfaces. |
| `frontend/public/brand/logo-full-dark.svg` | Mark + wordmark, paper wordmark. For dark surfaces. |
| `frontend/public/brand/favicon.svg` | 32 × 32 tile mark for browser tabs. |

### 4.4 Usage rules

- **Clear space.** Keep a margin equal to the height of one row (5/48 of the tile) around the mark, and half the tile height around the full logo, free of other elements.
- **Minimum size.** Tile mark: 16 px (favicon) is the floor; prefer 20 px and above in interfaces. Full logo: 96 px wide minimum on screen, 24 mm in print.
- **Colour.** The tile is always `brand-700` (light) or `brand-600` (dark) with a paper glyph, or the mono glyph in a single colour. Never recolour the glyph into semantic colours, never apply gradients, shadows, outlines or transparency.
- **Lock-up.** The mark sits to the left of the wordmark, vertically centred, gap equal to 14/36 of the mark height. Do not stack the wordmark under the mark except in square-constrained print contexts.
- **Do not** rotate, skew, animate the glyph rows individually, place the tile on a photograph, or add a tagline inside the lock-up.
- **Sidebar.** Use `logo-mark-mono.svg` in paper on the petrol sidebar, with the wordmark set as live text in the UI typeface. The coloured tile does not read on the petrol sidebar (1.7:1).

---

## 5. Colour system

### 5.1 Principle

One brand hue, **petrol** (a deep blue-green, hue 195), carries everything that is EduCore: primary actions, the sidebar, links, selection, focus. Everything else is a cool grey with a faint petrol cast. Semantic colours (success, warning, danger, info) are reserved for *state* and never used decoratively. Pure black and pure white are not used for text or large surfaces; the darkest ink is `#13222B`, the lightest paper is `#F2F4F5`.

Why petrol: it is distinct from the indigo that every admin template defaults to, distinct from the green used for *success*, distinct from the blue used for *info*, and it reads as institutional rather than promotional.

### 5.2 Primitive scales

**Brand (petrol)**

| Step | Hex | Role |
|---|---|---|
| 50 | `#EEF5F7` | Faint tint |
| 100 | `#E1EEF1` | Soft fill (light): selected row, soft badge |
| 200 | `#BFDCE3` | Soft border |
| 300 | `#93C4D0` | Decorative |
| 400 | `#5FA7B8` | Charts, mid tone |
| 500 | `#2F86A0` | Decorative, dark-mode borders |
| 600 | `#1F6E84` | Primary button (dark) |
| 700 | `#135263` | Primary button, link (light) |
| 800 | `#0F4452` | Primary hover, brand text (light) |
| 900 | `#0E2A34` | Sidebar (light) |
| 950 | `#091D25` | Deepest |

**Neutral (cool grey, petrol cast)**

| Step | Hex | Step | Hex |
|---|---|---|---|
| 0 | `#FFFFFF` | 500 | `#5B6B74` |
| 50 | `#F7F9FA` | 600 | `#45555F` |
| 100 | `#F2F4F5` | 700 | `#2E3C45` |
| 150 | `#E9EDEF` | 750 | `#27343B` |
| 200 | `#D5DCE0` | 800 | `#1B262C` |
| 300 | `#AEBAC1` | 850 | `#151E23` |
| 400 | `#8794A0` | 900 | `#0E1518` |
| | | 950 | `#0B1114` |

**Semantic**

| Role | Light text | Light fill | Light solid | Dark text | Dark fill | Dark solid |
|---|---|---|---|---|---|---|
| Success | `#1E6B3C` | `#E2F2E7` | `#237A45` | `#7FD19C` | `#13302A` | `#2E8B52` |
| Warning | `#7A4E00` | `#FBEFD4` | `#9A6500` | `#E6BC5C` | `#32280F` | `#B37A14` |
| Danger | `#A32A2A` | `#FBE5E5` | `#B83232` | `#F09A98` | `#3A1B1B` | `#C94A4A` |
| Info | `#2C5591` | `#E5ECF7` | `#3763A3` | `#9DBCEC` | `#1A2639` | `#4A78BE` |

### 5.3 Semantic tokens, light theme

| Token | Hex | Use |
|---|---|---|
| canvas | `#F2F4F5` | Page background |
| surface | `#FFFFFF` | Cards, tables, dialogs |
| surface-subtle | `#F7F9FA` | Table header, row hover |
| surface-sunken | `#E9EDEF` | Wells, disabled fields |
| border | `#D5DCE0` | Hairlines, dividers, card edges |
| border-strong | `#AEBAC1` | Emphasised dividers |
| border-input | `#8794A0` | Input and select borders (3:1 non-text) |
| text | `#13222B` | Primary text |
| text-secondary | `#45555F` | Labels, table headers, descriptions |
| text-muted | `#5B6B74` | Placeholders, hints, timestamps |
| text-disabled | `#8794A0` | Disabled labels (exempt from contrast) |
| brand | `#135263` | Primary button, active indicators |
| brand-hover | `#0F4452` | Primary hover |
| brand-text | `#0F4452` | Links, brand-coloured text |
| brand-soft | `#E1EEF1` | Selected rows, soft badges |
| focus-ring | `#1F7A94` | 2 px focus outline |
| sidebar | `#0E2A34` | Navigation rail |
| sidebar-text | `#D6E3E8` | Nav labels |
| sidebar-muted | `#8FA7B1` | Nav section labels, role line |
| sidebar-active | `#1D4D5D` | Active nav item fill |

### 5.4 Semantic tokens, dark theme

| Token | Hex | Use |
|---|---|---|
| canvas | `#0E1518` | Page background |
| surface | `#151E23` | Cards, tables, dialogs |
| surface-subtle | `#192429` | Table header, row hover |
| surface-raised | `#1B262C` | Popovers, menus, toasts |
| surface-sunken | `#0B1114` | Wells |
| border | `#27343B` | Hairlines |
| border-strong | `#3A4A53` | Emphasised dividers |
| border-input | `#5C6C75` | Input and select borders (3:1 non-text) |
| text | `#E4EAED` | Primary text |
| text-secondary | `#A7B4BB` | Labels, headers |
| text-muted | `#7F8D95` | Placeholders, hints |
| text-disabled | `#5C6C75` | Disabled labels |
| brand | `#1F6E84` | Primary button |
| brand-hover | `#1C667B` | Primary hover (slightly deeper, label stays white) |
| brand-text | `#7BC2D4` | Links |
| brand-soft | `#13303A` | Selected rows, soft badges |
| focus-ring | `#5FB2CA` | 2 px focus outline |
| sidebar | `#0A1215` | Navigation rail |
| sidebar-text | `#D6E3E8` | Nav labels |
| sidebar-muted | `#86999F` | Section labels |
| sidebar-active | `#173540` | Active nav fill |

### 5.5 Contrast ratios (WCAG 2.2, computed)

Targets: 4.5:1 for text under 24 px / 18.66 px bold (AA), 3:1 for large text and for non-text UI boundaries (1.4.11). Every text pair below passes AA; most pass AAA.

**Light theme**

| Pair | Foreground on background | Ratio | Level |
|---|---|---|---|
| Body text on surface | `#13222B` / `#FFFFFF` | 16.26:1 | AAA |
| Body text on canvas | `#13222B` / `#F2F4F5` | 14.74:1 | AAA |
| Secondary text on surface | `#45555F` / `#FFFFFF` | 7.73:1 | AAA |
| Secondary text on canvas | `#45555F` / `#F2F4F5` | 7.00:1 | AAA |
| Secondary text on table header | `#45555F` / `#F7F9FA` | 7.32:1 | AAA |
| Muted text on surface | `#5B6B74` / `#FFFFFF` | 5.53:1 | AA |
| Muted text on canvas | `#5B6B74` / `#F2F4F5` | 5.01:1 | AA |
| Link / brand text on surface | `#0F4452` / `#FFFFFF` | 10.67:1 | AAA |
| Brand text on canvas | `#135263` / `#F2F4F5` | 7.88:1 | AAA |
| Primary button label | `#FFFFFF` / `#135263` | 8.70:1 | AAA |
| Primary button label, hover | `#FFFFFF` / `#0F4452` | 10.67:1 | AAA |
| Soft brand badge | `#0F4452` / `#E1EEF1` | 8.99:1 | AAA |
| Text on selected row | `#13222B` / `#E1EEF1` | 13.71:1 | AAA |
| Sidebar label | `#D6E3E8` / `#0E2A34` | 11.44:1 | AAA |
| Sidebar muted label | `#8FA7B1` / `#0E2A34` | 5.95:1 | AA |
| Sidebar active label | `#D6E3E8` / `#1D4D5D` | 7.05:1 | AAA |
| Success badge | `#1E6B3C` / `#E2F2E7` | 5.61:1 | AA |
| Success text on surface | `#1E6B3C` / `#FFFFFF` | 6.51:1 | AA |
| Warning badge | `#7A4E00` / `#FBEFD4` | 6.31:1 | AA |
| Warning text on surface | `#7A4E00` / `#FFFFFF` | 7.20:1 | AAA |
| Danger badge | `#A32A2A` / `#FBE5E5` | 5.97:1 | AA |
| Danger text on surface | `#A32A2A` / `#FFFFFF` | 7.19:1 | AAA |
| Destructive button label | `#FFFFFF` / `#B83232` | 5.93:1 | AA |
| Info badge | `#2C5591` / `#E5ECF7` | 6.28:1 | AA |
| Info text on surface | `#2C5591` / `#FFFFFF` | 7.46:1 | AAA |
| Input border on surface (non-text) | `#8794A0` / `#FFFFFF` | 3.10:1 | passes 3:1 |
| Focus ring on surface (non-text) | `#1F7A94` / `#FFFFFF` | 4.92:1 | passes 3:1 |
| Focus ring on canvas (non-text) | `#1F7A94` / `#F2F4F5` | 4.46:1 | passes 3:1 |

**Dark theme**

| Pair | Foreground on background | Ratio | Level |
|---|---|---|---|
| Body text on surface | `#E4EAED` / `#151E23` | 13.92:1 | AAA |
| Body text on canvas | `#E4EAED` / `#0E1518` | 15.18:1 | AAA |
| Body text on raised | `#E4EAED` / `#1B262C` | 12.71:1 | AAA |
| Secondary text on surface | `#A7B4BB` / `#151E23` | 7.96:1 | AAA |
| Secondary text on raised | `#A7B4BB` / `#1B262C` | 7.27:1 | AAA |
| Muted text on surface | `#7F8D95` / `#151E23` | 4.95:1 | AA |
| Muted text on raised | `#7F8D95` / `#1B262C` | 4.52:1 | AA |
| Link / brand text on surface | `#7BC2D4` / `#151E23` | 8.46:1 | AAA |
| Brand text on canvas | `#7BC2D4` / `#0E1518` | 9.23:1 | AAA |
| Primary button label | `#FFFFFF` / `#1F6E84` | 5.80:1 | AA |
| Primary button label, hover | `#FFFFFF` / `#1C667B` | 6.49:1 | AA |
| Soft brand badge | `#9AD2E0` / `#13303A` | 8.38:1 | AAA |
| Text on selected row | `#E4EAED` / `#13303A` | 11.44:1 | AAA |
| Sidebar label | `#D6E3E8` / `#0A1215` | 14.42:1 | AAA |
| Sidebar muted label | `#86999F` / `#0A1215` | 6.37:1 | AA |
| Sidebar active label | `#D6E3E8` / `#173540` | 9.89:1 | AAA |
| Success badge | `#7FD19C` / `#13302A` | 7.75:1 | AAA |
| Success text on surface | `#7FD19C` / `#151E23` | 9.26:1 | AAA |
| Warning badge | `#E6BC5C` / `#32280F` | 8.10:1 | AAA |
| Warning text on surface | `#E6BC5C` / `#151E23` | 9.43:1 | AAA |
| Danger badge | `#F09A98` / `#3A1B1B` | 7.23:1 | AAA |
| Danger text on surface | `#F09A98` / `#151E23` | 7.88:1 | AAA |
| Destructive button label | `#FFFFFF` / `#C94A4A` | 4.60:1 | AA |
| Info badge | `#9DBCEC` / `#1A2639` | 7.85:1 | AAA |
| Info text on surface | `#9DBCEC` / `#151E23` | 8.73:1 | AAA |
| Input border on surface (non-text) | `#5C6C75` / `#151E23` | 3.11:1 | passes 3:1 |
| Focus ring on surface (non-text) | `#5FB2CA` / `#151E23` | 7.01:1 | passes 3:1 |

Notes from the computation:

- Hairline borders (`#D5DCE0`, `#27343B`) are decorative at about 1.3:1 and are never the only indicator of a control. Inputs use `border-input`.
- Solid success, warning and info fills carry no text in the dark theme (white on `#2E8B52` is 4.26:1, on `#4A78BE` 4.45:1). They are used for dots, bars and icons only. In the light theme white on success (5.33:1), warning (4.96:1) and info (6.04:1) solids passes, but the only solid semantic button in EduCore is the destructive one.
- Disabled text is exempt from WCAG contrast; it still stays above 2.5:1 so it remains legible.
- Muted text is not placed on `surface-sunken` in the light theme (4.3:1). Use `text-secondary` there.

### 5.6 Colour rules

1. One accent per screen: petrol. Semantic colours appear only on badges, inline validation, toasts and the destructive button.
2. No gradients on surfaces, buttons or text. The only permitted gradient is a 1-step scrim over an image, and EduCore has no images.
3. No coloured icon tiles beside stat numbers; the number is the emphasis.
4. Tinted fills (`*-soft`, semantic fills) always pair with their matching text colour, never with `text`.
5. Red means destructive or failed. It is never used for "admin", "important" or emphasis. The old red-on-pink logout button and red role badge are retired.

---

## 6. Typography

### 6.1 Families (open licence, self-hosted)

| Role | Family | Package | Weights |
|---|---|---|---|
| Interface and body | **IBM Plex Sans** | `@fontsource/ibm-plex-sans` (or `@fontsource-variable/ibm-plex-sans`) | 400, 500, 600 |
| Data: identifiers, IPs, CIDR, timestamps, counts in tables, code | **IBM Plex Mono** | `@fontsource/ibm-plex-mono` | 400, 500 |

Why Plex: it was designed for a technology institution, has complete Latin Extended coverage for Turkish (ş, ğ, ı, İ, ç, ö, ü, with correct dotless-i behaviour), has a matching monospace with the same x-height and colour, is SIL Open Font Licensed and ships on Fontsource for self-hosting. No font is loaded from a third-party CDN; `font-display: swap` is required.

Fallback stacks: `"IBM Plex Sans", "Segoe UI", system-ui, -apple-system, sans-serif` and `"IBM Plex Mono", "Cascadia Mono", Consolas, ui-monospace, monospace`.

### 6.2 Scale

Root is 16 px; interface base is 14 px because the product is data-dense.

| Token | Size | Line height | Weight | Letter-spacing | Use |
|---|---|---|---|---|---|
| text-4xl | 36 px | 1.1 | 600 | -0.02em | Stat tile figures (tabular) |
| text-3xl | 28 px | 1.2 | 600 | -0.02em | Page title |
| text-2xl | 22 px | 1.25 | 600 | -0.01em | Dialog title, section title |
| text-xl | 18 px | 1.3 | 600 | -0.01em | Card title |
| text-lg | 16 px | 1.5 | 400 | 0 | Lead paragraph, login labels |
| text-base | 14 px | 1.5 | 400 | 0 | Body, table cells, inputs, buttons |
| text-md | 13 px | 1.45 | 400 | 0 | Dense table cells, secondary rows |
| text-sm | 12 px | 1.4 | 500 | 0 | Badges, captions, table headers |
| text-xs | 11 px | 1.3 | 500 | +0.04em | Mono labels, section eyebrows in sidebar (uppercase) |

Rules:

- Headings use weight 600, never 700 or above. Emphasis in running text uses 500.
- All numbers in tables, stat tiles and badges use `font-variant-numeric: tabular-nums`.
- Identifiers (student number, IP, CIDR, job id, file name) are set in Plex Mono at the same size as surrounding text.
- Uppercase is restricted to the 11 px sidebar section labels and table column headers; nowhere else.
- Maximum measure for paragraphs: 65ch.
- Sentence case everywhere, including buttons and titles ("New student", not "New Student").

---

## 7. Spacing, radius, elevation

### 7.1 Spacing (4 px base)

`2, 4, 8, 12, 16, 20, 24, 32, 40, 48, 64` px, exposed as `--space-0-5` through `--space-16`. Component internals use 8/12/16; page gutters 24 (desktop) and 16 (mobile); section gaps 32.

Density presets for tables: comfortable (row 48 px), compact (row 40 px). Default is compact on Students, Job logs, IP rules and Users; comfortable on Dashboard.

### 7.2 Radius

One documented shape system:

| Token | Value | Applies to |
|---|---|---|
| radius-xs | 3 px | Badges, checkboxes, kbd |
| radius-sm | 6 px | Buttons, inputs, selects, nav items, menu items |
| radius-md | 8 px | Cards, tables, popovers, toasts |
| radius-lg | 12 px | Dialogs, the logo tile |
| radius-full | 999 px | Avatars and status dots only |

Badges are *not* pills. The square-cornered badge is one of the deliberate departures from the default admin look.

### 7.3 Elevation

Structure comes from hairlines first and shadows second. Shadows are tinted with the petrol ink, never neutral black on light surfaces.

| Level | Light | Dark | Use |
|---|---|---|---|
| 0 | none, 1 px `border` | same | Cards, tables, sidebar |
| 1 | `0 1px 2px rgba(14,42,52,.06)` | `0 1px 2px rgba(0,0,0,.4)` | Sticky table header, input hover |
| 2 | `0 2px 6px rgba(14,42,52,.08), 0 1px 2px rgba(14,42,52,.06)` | `0 4px 12px rgba(0,0,0,.45)` | Popovers, menus, toasts |
| 3 | `0 12px 32px -8px rgba(14,42,52,.18), 0 2px 6px rgba(14,42,52,.08)` | `0 24px 48px -12px rgba(0,0,0,.6)` | Dialogs |

Dialog backdrop: `rgba(14,42,52,.48)` light, `rgba(0,0,0,.6)` dark. No blur.

---

## 8. Iconography

- One family, one stroke. The frontend already depends on `lucide-react`; it stays, with a global `strokeWidth` of 1.75 and sizes 16 (inline, table actions), 20 (nav, section titles) and 24 (empty states). Do not mix in a second icon set or hand-drawn SVG icons.
- Icons are monochrome and inherit text colour. They never carry a coloured background tile.
- **No emoji anywhere in the interface**: not in navigation (the old house, graduation cap and books glyphs are retired), not in the weather widget, not in toasts, not in log lines. Status is shown with an icon from the set plus a text label.
- Icon-only buttons require an `aria-label` and a tooltip; prefer icon + label in tables where width allows.
- Navigation icon map: Dashboard `LayoutGrid`, Students `Users`, Courses `BookOpen`, Profile `UserRound`, Job logs `ScrollText`, IP rules `Network`, Users `ShieldCheck`, Sign out `LogOut`.
- Status icon map: success `CircleCheck`, warning `TriangleAlert`, failed `CircleX`, info `Info`, loading `LoaderCircle` (rotating, respects reduced motion).

---

## 9. Motion

Motion is feedback, not decoration.

| Token | Value | Use |
|---|---|---|
| duration-fast | 120 ms | Hover, focus, colour changes |
| duration-base | 180 ms | Menus, popovers, toasts, row selection |
| duration-slow | 260 ms | Dialogs, page section entry |
| ease-standard | `cubic-bezier(0.2, 0, 0, 1)` | Default |
| ease-enter | `cubic-bezier(0, 0, 0.2, 1)` | Elements appearing |
| ease-exit | `cubic-bezier(0.4, 0, 1, 1)` | Elements leaving |

Rules:

1. Only `opacity` and `transform` animate. No `height`, `top`, `box-shadow` or colour transitions longer than 120 ms.
2. Buttons do not lift on hover. Press feedback is a `transform: translateY(0.5px)` on `:active` plus the colour change.
3. Dialogs: backdrop fades in, panel fades from 98% scale. Toasts: fade and slide 8 px from the edge. Table rows: no entry animation; data must appear instantly.
4. Loading: skeleton blocks in `surface-sunken` with a 1.2 s opacity pulse, matching the final layout. Spinners only for sub-second button states.
5. `@media (prefers-reduced-motion: reduce)`: all durations become 1 ms, the skeleton pulse stops, the loader icon stops rotating and is replaced by the static icon with the label "Loading".

---

## 10. Components

### 10.1 Buttons

| Variant | Fill | Text | Border | Use |
|---|---|---|---|---|
| Primary | brand | on-brand | none | One per view: the main create or submit action |
| Secondary | surface | text | border-input | Cancel, Back, secondary row actions |
| Ghost | transparent | text-secondary | none | Table actions, icon buttons; hover fill `surface-subtle` |
| Destructive | danger-solid | white | none | Confirmed deletes only, never in a table row |
| Destructive ghost | transparent | danger-text | none | Delete in table rows; confirms before acting |

Height 36 px (32 px in compact tables), padding 0 12 px, radius-sm, weight 500, 14 px. Icon + label gap 8 px. Disabled: `surface-sunken` fill, `text-disabled`, no border change, cursor default. Focus: 2 px `focus-ring` outline with 2 px offset. No "Update" button in amber; editing uses the primary button with the label "Save changes".

### 10.2 Inputs, selects, checkboxes

- Label above, 12 px / 500 / text-secondary. Required marker is the text "(required)" after the label, not an asterisk. Optional fields say nothing.
- Field: height 36 px, surface fill, 1 px border-input, radius-sm, 14 px text, 12 px horizontal padding. Placeholder in text-muted and never used as the label.
- Hover: `input-border-hover` (text-muted: 5.5:1 on surface in light, 4.9:1 in dark), so the hovered frame stays at or above 3:1. `border-strong` is for dividers only and is too faint for an input frame. Focus: border brand plus 2 px focus-ring outline. Error: border danger-solid, helper text in danger-text below the field with the `CircleX` icon at 16 px.
- Selects use the same frame with a 16 px `ChevronDown` glyph at the right. Native `<select>` is acceptable; it must still receive the frame styles.
- Checkboxes: 16 px, radius-xs, border-input; checked state fills brand with a white check. Do not scale checkboxes with `transform`.
- Monospace inputs (IP, CIDR, student number) use Plex Mono.

### 10.3 Tables (data-dense admin)

- Container: surface, 1 px border, radius-md, no shadow. Horizontal scroll inside the container on narrow screens, with the first column sticky.
- Header: surface-subtle fill, 12 px / 500 / text-secondary, uppercase with +0.04em tracking, 40 px tall, sticky within the scroll container. Sortable columns show `ArrowUpDown` at 14 px; the active sort shows `ArrowUp`/`ArrowDown` in brand-text. Never print the sort direction as text in the header.
- Rows: 40 px (compact) or 48 px, 1 px `border` between rows, no zebra striping. Hover: surface-subtle. Selected: brand-soft with a 2 px brand inset bar on the left edge.
- Alignment: text left, numbers right, actions right. Identifiers and numbers in Plex Mono with tabular numerals.
- Deleted records: text in text-muted plus a neutral badge "Deleted"; no reduced opacity on the whole row.
- Row actions: ghost buttons, 32 px, revealed at full opacity (never hidden until hover, for touch).
- Pagination: a right-aligned bar under the table with "1 to 8 of 42" in text-secondary and two secondary icon buttons. Page size selector on the left when applicable.
- Bulk selection: a selection bar replaces the table toolbar, shows "3 selected", and groups the actions. The bar uses brand-soft fill.
- Empty table: see 10.7, rendered inside the table container, minimum 240 px tall.

### 10.4 Cards and page structure

- Page header: title (text-3xl) on the left, a one-line description in text-secondary under it, primary action on the right. No icon beside the page title.
- Cards are used only when there are two or more peer groups on a page (Dashboard tiles, Student detail columns). A single table on a page is not wrapped in an extra card.
- Card: surface, 1 px border, radius-md, 20 px padding, title text-xl. No drop shadow at rest.
- Stat tile: label (text-sm, text-secondary, uppercase), value (text-4xl, tabular), optional footnote (text-md, text-muted). No icon tile, no colour.

### 10.5 Badges

- 20 px tall, 12 px / 500, 6 px horizontal padding, radius-xs, tinted fill with matching text colour, optional 12 px leading icon.
- Variants: neutral (surface-sunken / text-secondary), brand (brand-soft / brand-text), success, warning, danger, info.
- Mapping: *Enrolled* success; *Success* success; *Partial* warning; *Failed* danger; *Deleted* neutral; entity type `STUDENTS`/`COURSES` neutral; IP type `STATIC`/`RANGE`/`CIDR` brand; role `ADMIN` brand, `USER` neutral; assigned IP address rendered as a mono value, not a badge.
- Badge text is a word, never a sentence; no parentheses, no emoji.

### 10.6 Toasts

- Bottom-right, 360 px max, surface-raised, 1 px border, radius-md, elevation 2, 12 px padding, 16 px icon in the semantic colour, title in text (500), optional second line in text-secondary.
- One sentence, no exclamation marks: "Student added." / "Öğrenci eklendi." Errors say what to do: "Could not save the course. Check the term and try again."
- Auto-dismiss 4 s for success, 8 s for errors, persistent if an action button is present. Maximum 3 stacked.

### 10.7 Empty states

- Centred in the container; 24 px icon in text-muted, title text-lg / 500, one line of guidance in text-secondary, and the primary action if the user may create the missing thing.
- Copy pattern: "No students yet." + "Add the first student or run a CSV import." Never "Oops" or "Nothing to see here".
- Filtered-empty is different from truly empty: "No students match 'ay'." with a "Clear search" ghost button.

### 10.8 Sidebar and navigation

- Width 248 px, sidebar fill, full height, sticky. Padding 16 px. Collapses to a 56 px icon rail at 1024 px and to a top bar with a menu button under 768 px.
- Header: mono mark in paper at 24 px plus the wordmark "EduCore" as live text (text-lg, 600, sidebar-text).
- Groups with 11 px uppercase labels in sidebar-muted: *Registry* (Dashboard, Students, Courses, Profile), *Administration* (Job logs, IP rules, Users; visible to `ADMIN` only).
- Item: 36 px, radius-sm, icon 20 px, label 14 px / 500, sidebar-text at rest; hover `rgba(255,255,255,.06)`; active fill sidebar-active with white text and a 2 px paper bar on the left edge.
- Footer: avatar (initials, 32 px, radius-full, brand-soft on brand-text), name in sidebar-text, role as a small badge using the sidebar badge tokens (`sidebar-badge-bg` = sidebar-active fill, `sidebar-badge-text` = sidebar-text, 1 px `sidebar-badge-border` = sidebar-muted, at least 5.9:1 against the sidebar), because the neutral badge fill disappears on the dark sidebar, and a ghost "Sign out" button with the `LogOut` icon. The red logout button is retired.

### 10.9 Dialogs

- 480 px (forms) or 640 px (log details), surface, radius-lg, elevation 3, 24 px padding. Title text-2xl, optional description text-secondary. Footer right-aligned: secondary Cancel, primary Save. Destructive confirmations use the destructive button and name the object: "Delete course 'Databases I'?".
- Replace every `window.confirm` with this dialog.

---

## 11. Voice and tone

### 11.1 Principles

1. **Plain and specific.** Name the object and the result. "Course added." not "Operation successful!"
2. **Calm.** No exclamation marks, no "Oops", no "Awesome". Errors are stated, then the next step is given.
3. **One language per interface.** The user's locale decides; never mix English labels with Turkish toasts on the same screen.
4. **Sentence case** for everything, including buttons and headings. Proper nouns and `EduCore` keep their capitals.
5. **Verbs on buttons.** "Add student", "Save changes", "Delete", "Sign in". Never "OK", "Submit", "Yes".

### 11.2 Glossary, English and Turkish

| Concept | English | Turkish |
|---|---|---|
| Product | EduCore | EduCore |
| Navigation group | Registry / Administration | Kayıt / Yönetim |
| Dashboard | Dashboard | Genel bakış |
| Students | Students | Öğrenciler |
| Student number | Student number | Öğrenci numarası |
| Courses | Courses | Dersler |
| Term | Term | Dönem |
| Instructor | Instructor | Öğretim görevlisi |
| Enrol / Enrolled | Enrol, Enrolled | Kaydol, Kayıtlı |
| Profile | My profile | Profilim |
| Job logs | Job logs | İş kayıtları |
| Import | Import | İçe aktarım |
| IP rules | IP rules | IP kuralları |
| Single address / Range / Subnet | Single address, Range, Subnet (CIDR) | Tek adres, Aralık, Alt ağ (CIDR) |
| Users | Users | Kullanıcılar |
| Role: Administrator / User | Administrator, User | Yönetici, Kullanıcı |
| Sign in / Sign out | Sign in, Sign out | Oturum aç, Oturumu kapat |
| Deleted (soft) | Deleted | Silinmiş |
| Show deleted | Show deleted students | Silinmiş öğrencileri göster |
| Unassigned | Not assigned | Atanmamış |
| Status: Success / Partial / Failed | Success, Partial, Failed | Başarılı, Kısmi, Başarısız |

### 11.3 Message patterns

| Situation | English | Turkish |
|---|---|---|
| Create success | Student added. | Öğrenci eklendi. |
| Update success | Changes saved. | Değişiklikler kaydedildi. |
| Delete success | Course deleted. | Ders silindi. |
| Delete confirm | Delete student 2601005? This hides the record; it can be restored by an administrator. | 2601005 numaralı öğrenci silinsin mi? Kayıt gizlenir; bir yönetici geri alabilir. |
| Validation | Enter an IPv4 address such as 192.168.1.5. | 192.168.1.5 gibi bir IPv4 adresi girin. |
| Range validation | The end address must be greater than or equal to the start address. | Bitiş adresi başlangıç adresinden küçük olamaz. |
| Sign-in failure | The username or password is incorrect. | Kullanıcı adı veya parola hatalı. |
| Session expired | Your session has ended. Sign in again to continue. | Oturumunuz sona erdi. Devam etmek için yeniden oturum açın. |
| No permission | You do not have access to this page. | Bu sayfaya erişim yetkiniz yok. |
| Network error | EduCore could not reach the server. Check your connection and try again. | EduCore sunucuya ulaşamadı. Bağlantınızı kontrol edip yeniden deneyin. |
| Empty list | No courses yet. | Henüz ders yok. |
| Filtered empty | No students match "{query}". | "{query}" ile eşleşen öğrenci yok. |
| Loading | Loading students | Öğrenciler yükleniyor |

Turkish specifics: use the formal second person plural (*siz*), avoid abbreviations in UI copy, use the Turkish apostrophe when inflecting numbers and names ("2601005'i"), and keep dates in `dd.MM.yyyy HH:mm` for `tr-TR` and `d MMM yyyy, HH:mm` for `en-GB`.

Never shown to users: raw exception messages, stack traces, HTTP status codes, internal entity names such as `Account`, test credentials.

---

## 12. Screen-by-screen design direction

The structure, routes and data of each screen stay as they are. These directions describe the target look and the specific defaults to retire.

### 12.1 Sign in (`/login`)

- Full-height canvas. A 400 px surface card with radius-lg and a hairline border, vertically centred, with 32 px padding. No shadow at rest.
- Top: `logo-full.svg` at 28 px height (dark variant in dark theme). Title "Sign in to EduCore" text-2xl. No icon above the title.
- Fields: Username, Password (with a ghost show/hide toggle), labels above. Primary button full width: "Sign in". Error appears inline above the button as a danger badge row, not only as a toast.
- Retire the printed test credentials. If demo accounts must be exposed, they belong in the README, not the sign-in page.
- Footer line in text-muted: "EduCore · Student and course registry". This is the only place a descriptor is allowed.

### 12.2 Access denied (`/unauthorized`)

- Same card frame as sign in. 24 px `Lock` icon in text-muted, title "You do not have access to this page" (text-2xl), one line of guidance, primary button "Sign in". No giant watermark "401", no red circles, no "Oops".

### 12.3 Dashboard (`/`)

- Page header: "Dashboard" with the description "Registry overview". The greeting moves to the sidebar footer where the name already lives; a greeting is not a page title.
- Stat tiles: a 2-up row (expands to 3-up if a third metric is added), each a card with label, tabular figure, footnote ("as of 14:32"). No icon tiles, no colour.
- "Recently added students": a compact table (Student number in mono, Full name), 5 rows, with a ghost "View all students" link in the card header.
- The weather widget leaves the content area. It becomes a 32 px-tall mono line in the top bar: "Ankara 18°, clear", with a `CloudSun`/`Sun`/`CloudRain` icon from the icon set and a text-only city selector. No gradient card, no emoji, no fixed 100 px spacer above the content.

### 12.4 Students (`/students`)

- Page header: "Students", description "Search, edit and enrol students", primary action "Add student" (admins).
- Toolbar on one line: search field (320 px, leading `Search` icon, placeholder "Search by name or number"), a toggle styled as a secondary button with a checkbox feel: "Show deleted". The red "Deleted Students" button is retired.
- Table columns: Student number (mono), Name (initials avatar 28 px + name), IP address (mono or "Not assigned" in text-muted), Actions (ghost "Courses" with `ChevronRight`; admins also get ghost `Pencil` and destructive-ghost `Trash2`, both labelled).
- Sorting via the Name header icon; no "(ASC)" text.
- Deleted rows: text-muted name plus the neutral "Deleted" badge.
- Dialogs: "Add student" and "Edit student" with Save changes as primary. IP assignment field: a select labelled "IP source" (Manual, or an existing rule) followed by a mono input; pool hints become helper text under the field, not a toast with an emoji.

### 12.5 Student detail (`/students/:id`)

- Breadcrumb "Students / 2601005" in text-secondary; ghost "Back" is redundant and is removed.
- Header: student name as the title, student number in mono under it, IP address and status badges on the right.
- Two cards side by side (stack under 1024 px): "Enrolled courses" and "Course catalogue". Each course row is a 48 px list item with hairline separators (no nested bordered boxes): course name (500), term and instructor in text-secondary, and on the right either the success badge "Enrolled" or a secondary button "Enrol".
- Already-enrolled catalogue rows show a disabled secondary button "Enrolled", not a greyed card at 60% opacity.

### 12.6 Courses (`/courses`)

- Page header "Courses", description "Catalogue of active courses", primary "Add course".
- Default view is a table (Course, Term, Instructor, Students enrolled, Actions) because it sorts and scans better than cards. A card grid is permitted as an optional view toggle; if used, cards are surface with hairline border, title text-xl, and metadata as plain text lines, with the badge reserved for term.
- Edit and delete are ghost actions on the row, not floating amber and pink squares.

### 12.7 Profile (`/profile`)

- Title "My profile", description "Your details and term courses". A details card (name, student number mono, role badge) above the same two-card enrolment layout as 12.5, with the button label "Select" in place of "Enrol".

### 12.8 Job logs (`/logs`)

- Title "Job logs", description "Automated student and course imports". Non-admins see the access denied frame from 12.2 inside the content area.
- Table: checkbox, File (mono), Entity (neutral badge), Started (mono, locale format), Succeeded (right, tabular), Failed (right, tabular; danger-text only when greater than zero), Status (badge), Actions (ghost "Details").
- Selection bar appears above the table when rows are selected: "2 selected", secondary "Download JSON", destructive "Delete".
- Details dialog (640 px): header with file name in mono and the status badge; then a list of record lines, each with a 16 px status icon in the semantic colour and the message in text. Lines are separated by hairlines, not individually boxed in green and pink.

### 12.9 IP rules (`/ips`)

- Title "IP rules", description "Allowed IPv4 addresses, ranges and subnets", primary "Add rule".
- Table: Type (brand badge: Single address / Range / Subnet), Definition (mono), Actions (destructive-ghost Delete with confirmation dialog).
- Add rule dialog: a segmented control for Type (three secondary buttons, one active), then the mono inputs with inline validation copy from 11.3. Validation errors stay inline; a toast is not raised for client-side validation.

### 12.10 Users (`/users`)

- Title "Users", description "Roles and access levels", search field in the toolbar.
- Table: Name, Identifier (mono), Role (brand badge "Administrator" or neutral badge "User"), Actions: a select labelled "Role" styled per 10.2. Changing the role opens a confirmation dialog naming the user and the new role. Red is not used for the administrator role.

### 12.11 Global shell

- Sidebar per 10.8, canvas content area with 24 px padding and a 1200 px maximum content width, left-aligned.
- Top bar (48 px) inside the content area: breadcrumb on the left, weather line and theme toggle (system / light / dark) on the right.
- Document title pattern: "Students · EduCore". Favicon: `favicon.svg`.
- Theme: respect `prefers-color-scheme`; the toggle writes `data-theme` on `<html>` and persists in local storage.

---

## 13. Checklist before shipping a screen

- [ ] Only petrol is used as an accent; semantic colours appear on state only.
- [ ] All text pairs are from section 5.5 or verified at 4.5:1 or better.
- [ ] No emoji, no second icon family, no icon tiles.
- [ ] Buttons and titles are in sentence case; one primary button per view.
- [ ] Identifiers and numbers are in Plex Mono with tabular numerals.
- [ ] Every list has loading, empty, filtered-empty and error states.
- [ ] Every destructive action confirms in a dialog that names the object.
- [ ] Copy is in one language per screen and follows the glossary.
- [ ] Reduced motion and dark theme have been checked in the browser.
