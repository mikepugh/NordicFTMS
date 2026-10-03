# Support diagnostics

The Troubleshooting section displays the NordicFTMS version, a selectable support
ID, and a Send diagnostic report button. For an affected user, request an app
update and their support ID (or a photo of this section). No ADB is required.

With detailed logging enabled, reports are collected when the app process starts,
when logging is enabled, and after backend failures. An existing enabled setting
survives an in-place APK update. Manual reports also work with detailed logging
off and do not require a working GlassOS connection or Bluetooth permissions.
Prefer an in-place update, not uninstall/reinstall. A fresh installation defaults
to detailed logging off; after reinstall, check the toggle (Android may restore
preferences from backup). Manual reports remain available either way.

## Finding a report

In the Sentry `nordicftms` project, search `support_id:NFT-<displayed value>` using
the complete displayed ID, for example `support_id:NFT-01234567890123456789`.
Remove `is:unresolved` and restrictive level filters when looking for reports.
Reports are informational events grouped as `NordicFTMS diagnostic report`;
ordinary errors also carry `support_id`. `diagnostic_reason` distinguishes
`app_start`, `logging_enabled`, `backend_unavailable`, `alternate_endpoint_selected`,
and `manual` reports.

The report contains three structured contexts:

- `support_diagnostics`: app/Android/device versions, clock and time zone,
  uptime, local name resolution, independent IPv4/IPv6 TCP checks on port 54321,
  and installed versions/enabled/stopped flags plus declared services for the
  five listed iFit packages. Declared services are not a running-process list.
- `glassos_connection`: timestamped outcomes for GetConsole, GetKnownConsoleInfo,
  and ConsoleChanged, plus channel state, endpoint, generation, and last failure.
  It also includes a bounded attempt timeline, eight recent endpoint summaries,
  the current cycle's original failure, local discovery progress, and TLS
  certificate fingerprints/validity dates and observed handshake/chain results.
  This describes actual connection attempts, not a new RPC test. An empty
  context means no attempt has been recorded in this process yet.
- `nordicftms_runtime`: service/backend/Bluetooth states, permissions, retry
  count, settings, console name/type/firmware, and last displayed error.

The package allowlist is `com.ifit.glassos_service`, `com.ifit.mithlond`,
`com.ifit.standalone`, `com.ifit.eru`, and `com.ifit.launcher`. The collector does
not enumerate unrelated installed apps, read iFit account data, or upload
certificates/private keys. It does not start, stop, or change any iFit service.

## Interpreting results

- TCP `refused` means the endpoint rejected the connection at the probe time.
  A successful TCP connection alone does not establish TLS or RPC compatibility.
- A package's `stopped_flag: false` does not prove its service is running.
  Other apps' service process state is explicitly reported as unobservable.
- `not_installed_or_not_visible` and `query_failed` remain distinct from a
  confirmed installed-but-disabled package. Android package visibility is
  declared for the exact allowlist; there is no QUERY_ALL_PACKAGES permission.
- RPC outcomes preserve the underlying transport causes and distinguish empty
  successful replies from failed/unsupported calls.
- `tls_configuration: ready` only means credentials loaded. Chain validation
  `accepted` does not prove a completed handshake, hostname verification, client
  authentication, or a working RPC. `presented_peer_leaf` describes the offered
  certificate, even when rejected; it is not a trusted identity claim.
- `tcp_refused` is a failure before certificate exchange. TLS/certificate failures,
  RPC access denial, unimplemented RPCs, deadlines, and empty console responses
  are distinct evidence. A missing handshake observation is not proof of a
  certificate mismatch. A server rejecting our client certificate may provide
  only a TLS alert, not its reason; we cannot manufacture that missing detail.

## Local endpoint discovery

The normal `localhost:54321` mTLS path always runs first with its existing console
readiness retries. After failure, a previously verified alternate endpoint from
this service instance is revalidated, then other loopback candidates are tried.
Successful normal connections perform no discovery or scan.

Listener discovery reads `/proc/net/tcp` and `tcp6` when permitted, considering
only listeners bound to loopback or wildcard addresses. No raw tables, process
IDs, or LAN addresses are collected. Android 10+ normally blocks these files.
When a table is unavailable or truncated, a resumable TCP connect-and-close scan
checks **only** `127.0.0.1` and `::1`, across ports 1-65535, starting at 54321.
Each pass stops at four seconds or 8192 probes, with a maximum 25 ms connect
timeout and at least 30 seconds between passes. The cursor continues across
backend retries while this service instance lives. A full sweep can take minutes;
`partial`, `cooldown`, `interrupted`, and `sweep_complete` must not be conflated.
Timeout/error counts are recorded, not treated as confirmed closed ports.

Up to 64 candidates are retained, with overflow explicitly reported. Beyond a
remembered endpoint, at most four candidates are validated per connection cycle,
rotating failed candidates with a 60-second cooldown. Candidate RPCs have 1.5-second
deadlines each and one round of console discovery. Deferred candidates are tried
on later cycles. All channels use the bundled CA/client credentials and the same
`localhost` hostname check. Only fresh, usable GlassOS console data makes an
endpoint ready; cached data or a successful TCP connection is insufficient.
No control commands are used during discovery.

This does not disable/stop iFit, claim USB devices, scrape telemetry logs, try
plaintext gRPC, or relax certificate checks. It can recover from a changed local
TCP endpoint, but cannot fix an absent backend, a Unix-socket-only backend, or an
incompatible certificate/API. Those cases need the captured evidence first.

Research informing the scope:
- The [bridge protocol notes](https://github.com/ciarancoffey/nordictrack-ftms-bridge/blob/9be05e2dd3f7294a886968ae7f9e33259af7d399/docs/PROTOCOL.md)
  describe a working Valinor log reader but an unresolved Unix-socket handshake,
  not a verified alternate TCP endpoint.
- [Hyperborea's iFit prerequisites](https://github.com/ball2jh/Hyperborea/blob/b7b10f33c2890f1bbbd5caace6c8a652b4a58961/ecosystem/ifit/src/main/java/com/nettarion/hyperborea/ecosystem/ifit/IfitEcosystemManager.kt)
  depend on disabling competing iFit apps for direct USB access. That path is
  deliberately excluded from NordicFTMS; no code from either backend was copied.
- [Android's /proc/net restrictions](https://developer.android.com/about/versions/10/privacy/changes#proc-net-filesystem)
  explain why listener-table inspection cannot be the only discovery mechanism.

Before release, test an in-place update on a working console with iFit left
enabled, verify normal FTMS telemetry/control still works, and collect a report
from an affected console. JVM tests cover discovery bounds, address restrictions,
progress/cancellation, certificate validation delegation, and Sentry serialization;
they do not establish compatibility with unknown GlassOS builds.

## Delivery and limits

Collection runs on a dedicated worker. TCP probes have a one-second timeout each
and send no application commands. Automatic reports are limited to one per
reason per ten minutes within a process; manual reports have a thirty-second
cooldown. Only one collection can run at a time. Turning detailed logging off
prevents further automatic collection/submission; it cannot recall reports
already queued. High-frequency NordicFTMS-Trace output remains local logcat.

The UI says **queued for delivery**, not sent: a Sentry event ID does not confirm
server receipt. Sentry handles transport/caching, and delivery requires internet
access. Builds without a configured SENTRY_DSN show reporting unavailable.
The normal release build must retain its Sentry DSN and bundled certificates.

Support IDs are random and stored in Android's no-backup directory independently
of Bluetooth identity. They survive restarts and in-place updates, but not app
data clearing or uninstall. Storage failure produces an explicitly temporary
ID for that process without blocking startup.

References: [Android package visibility](https://developer.android.com/training/package-visibility/declaring)
and [console package observations](https://github.com/ciarancoffey/nordictrack-ftms-bridge/blob/main/docs/ARCHITECTURE.md).
