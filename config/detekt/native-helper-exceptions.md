# Reviewed native/helper exceptions

These exact declaration signatures are manually selected exceptions, not refreshed warning
inventories. The warning multiset files are unchanged. Existing `CurrentIssues` entries are
preserved; only the signatures below enter `ManuallySuppressedIssues`. No source annotation
bypasses the configured `ForbiddenSuppress` rule.

| Boundary | Accepted rule | Reason and executable guard |
| --- | --- | --- |
| Platform `RawNativeTransfer.validate` | Complexity; literal UAPI table indices and widths | Keep the explicit syscall pointer schema auditable in one table; unsupported managed pointers still fail closed. Native transfer boundary tests validate negative, overflowing, undersized and missing schemas. |
| Platform `ControlSocketExchange.poll`, `SeccompSessionHandler.advancePending` | Return count | Each partial transfer yields immediately; timeout/terminal claims must not fall through to another reply. Notification deadline and control exchange tests exercise stalled peers and late completion. |
| Platform `SupervisorSocketUtils.connectDescriptorWithRetry` | Throw count | Native acquisition, deadline and interruption failures retain independent rollback boundaries; socket retry and descriptor ownership tests exercise failed acquisition. |
| Enforcer `SocketHelperMain.sendResult`, `SocketHelperProcess.executeAuthority`, `TraceeAuthorityProtocol.readRequest` | Throw count | Validate each private frame identity, bound and descriptor handoff before transfer. Authority protocol/helper contracts assert rejected frames and exact closure. |
| Enforcer `SupervisorNotificationWork.poll`, `TraceeAuthorityNotificationWork.poll` | Return count; authority interpreter complexity | Explicit Await boundaries prevent a poll from blocking through multiple stages; terminal outcomes relinquish ownership once. Authority reply and notification deadline contracts exercise every terminal transfer. |
| Enforcer `TraceeAuthorityHelper.serve`, `SeccompInstallTransport.connect` | Nesting | Nested arena, authority and acquired-descriptor scopes are the cleanup structure, not incidental control flow. Helper failure and installation ownership contracts exercise rollback. |
| Enforcer `TraceeProcAuthority.readable`, `readOnlyNul` | Throw count; loop jumps | Independent overflow, range, permission and malformed-map guards protect proc-memory access. Native authority tests cover unreadable memory and bounded read-only NUL capture. |
| Enforcer `SupervisorSeccompNotifInstaller.installOwned` | Throw count | Platform support, irreversible installation and handshake failures retain explicit partial-state failure boundaries. Installation handshake/deadline tests exercise these errors. |
| Profiler `HandshakeSession.Active.performHandshake`, notification work `advance` | Return count | Terminal handshake, publication and ACK outcomes yield independently; failed replies cannot publish or trigger another response. Handshake and session liveness tests cover terminal outcomes. |

Compatibility shims use narrowly reviewed `UnusedParameter` annotations because removing the
parameters would break linkage, while using them would fabricate descriptor ownership. Their
adjacent rationale and exact sites are listed in `approved-suppressions.txt`. One pre-installation
vararg composition retains the established policy API and has a scoped `SpreadOperator` entry.

The thresholds, forbidden-suppression configuration, full warning inventories, and coverage
exclusions are unchanged. Future signature changes require re-review rather than a baseline refresh.
