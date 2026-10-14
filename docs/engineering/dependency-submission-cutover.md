# Gradle dependency-submission cutover

Status: **STAGED / INACTIVE** until the administrator cutover is performed.

This supersedes the 2026-10-13 NO-GO recorded in #395. The earlier research
proved complete same-source graph parity: 509 resolved, 52 direct, 1,436
dependency edges and 509 purls were identical between managed JDK21/x64 and
JDK25/ARM64 generation. zMyBatis subsequently proved the repository-owned
generate-and-submit cutover pattern in production.

Cutover:
1. Merge while MARKFLOW_ADS_CUTOVER_DONE is absent/false.
2. Disable managed Automatic dependency submission; keep Dependency Graph, Dependabot and Dependency Review enabled.
3. Set MARKFLOW_ADS_CUTOVER_DONE=true.
4. Dispatch Gradle Dependency Submission on main.
5. Require exact-main provenance and graph coverage; stable reference is 509/52 and fail-closed floor is 450/40.
6. Confirm later non-default pushes no longer create managed ADS runs.

Rollback: set MARKFLOW_ADS_CUTOVER_DONE=false first, re-enable managed ADS, and require a fresh managed main snapshot. Never leave both publishers active.
