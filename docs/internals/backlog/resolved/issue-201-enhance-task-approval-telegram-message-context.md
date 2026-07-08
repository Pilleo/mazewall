---
title: "Enhance Task Approval Telegram Message with Full Context"
severity: "HIGH"
status: "resolved"
priority: 10
dependencies: []
component: "orchestrator"
effort: "small"
github_issue: 65
---

# 🔴 [Severity: HIGH]: Enhance Task Approval Telegram Message with Full Context

**Context:**
When the Autonomous Backlog Orchestrator requests approval to start a new task in `OrchestratorDaemon.kt:156`, the Telegram notification only displays the issue ID and title:
```
🤖 Request to start task issue-XXX
Title: Some issue title
```
This requires the developer to open the local backlog file or search GitHub to see what the issue is about, which slows down the approval process and breaks the convenience of remote Telegram interactions.

**Needed:**
Modify the Telegram approval request message formatting to extract and include the full context of the backlog issue.

Specifically:
1. **Read Backlog Details:** In `handleAwaitStartApproval()`, extract the core contents of the backlog file associated with the issue (`context.currentIssueFile`).
2. **Include Key Frontmatter and Content:** Show the metadata (Severity, Component, Priority, Effort) and the main body (Context and Needed sections) formatted nicely in markdown for Telegram.
3. **Handle Long Descriptions:** Since Telegram has a 4096-character limit, truncate or clean up the text if it is exceptionally long to prevent API errors.

### Proposed Format:
```markdown
🤖 *Approval Request: Start Task $issueId*
*Title:* $issueTitle
*Severity:* $severity | *Effort:* $effort | *Component:* $component

*Context:*
$issueContext

*Needed:*
$issueNeeded

Please approve or skip in the inline keyboard below.
```
