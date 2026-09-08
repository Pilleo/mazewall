# SMICS 2026 preparation package

Status: substantive draft, not ready to submit. Confirmed scope: mazewall, main-conference abstract. Nothing has been submitted or emailed.

Authorship and framing: Leanid Piliptsevich is the sole developer and author, submitting independently. The manuscript presents Linux behavioural containment as the central model, with JVM as the current implementation target. Portability to other language runtimes is an architectural argument, not a claim of existing tested ports.

## Prepared

- `mazewall_SMICS2026_DRAFT.odt`: editable manuscript built from the official main-conference template, preserving its styles and page geometry.
- `mazewall_SMICS2026_DRAFT.pdf`: five A4 pages, rendered in LibreOffice with embedded Libertinus fonts.
- `manuscript.md`: text for convenient review.
- `submission-email.txt`: unsent email draft.
- `evidence-notes.md`: evidence limitations and source provenance.
- `official-template.odt`: unmodified organizer template.
- `paper-content.json` and `build_document.py`: manuscript source and builder, preserving the organizer's template styles. This builder replaces the earlier temporary builder.

## Decisions and details needed

1. Topic and route confirmed by the author: mazewall, main-conference abstract. Proposed track: **Cyber Defense of Communication Systems and Cloud Infrastructure, Incident Management**.
2. Author details supplied: Leanid Piliptsevich; independent researcher; corresponding email pilleo19@gmail.com. The author explicitly clarified that this submission is not affiliated with EPAM. No postal address or ORCID was supplied; neither has been invented.
3. The author must review scientific claims, bibliography, and the AI disclosure. Confirm funding/acknowledgments if applicable.
4. Confirm whether this is original, unpublished work and whether any overlapping submission exists. Confirm each author's submission count and the complete self-citation count.
5. Author name, affiliation, and email have been added. Final front-matter review should address the template’s affiliation address and copyright information.
6. For a stronger empirical submission, supply or generate controlled, pinned results with the evidence described in `evidence-notes.md`. The current draft deliberately makes no fresh experimental or benchmark claims.

## Verified conference requirements

Checked on 7 September 2026 against https://smics.lnu.edu.ua/en/authors/ and https://smics.lnu.edu.ua/en/dates/.

Main conference: 3–5 full A4 pages, proper English, official LibreOffice ODT template, Libertinus fonts, no more than five authors, no more than two abstracts per author, and self-citations at most 30%. Required content covers the problem, novelty, proposed solution, and obtained results in the conclusions. Submission is by email to smics@lnu.edu.ua.

The current bibliography has eight references, including one project reference (12.5% if that is the only self-citation). It includes the published SBoB v0.0.3 proposal; the text distinguishes intended behaviour from enforcement and does not claim complete SBoB format support. The unused Security Manager reference was removed when the language section was shortened.

**The registration deadline was listed as 7 September 2026 when checked that day.** The dates page did not state a cutoff time. Acceptance notification: 10 September; invitations: 15 September; conference: 24–25 September. Registration form: https://forms.gle/wxu2zhuXg4vcPUCk8. Do not infer that registration and email submission have been completed.

Main template downloaded from the organizer's link: https://drive.google.com/file/d/1EkKjAGd789fb1nmaz9ZQcfAuANpwAqRc/view.

## Rendering checks

The four main numbered sections use the requested mandatory headings verbatim, in order: Statement of the problem in general terms; Scientific novelty compared to known works; Brief presentation of the proposed solution; Conclusions containing main obtained results. SBoB, language support, and protection limits are subsections within the proposed solution. References are renumbered by first appearance after reordering.

The draft was exported with LibreOffice 26.2.5.2. PDF inspection verified five A4 pages and embedded Libertinus Serif/Sans variants. All five rendered pages were visually inspected for clipping, overlap, and broken glyphs. Author placeholders have been replaced with the supplied details. Scientific and final front-matter review remain pending. Template margins and font sizes were not changed to force a page count.

No project source files or existing uncommitted work were changed. No research experiments or repository build checks were run for this writing task.
