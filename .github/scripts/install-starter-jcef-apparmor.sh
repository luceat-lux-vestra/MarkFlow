#!/usr/bin/env bash
# #353: CI-only scoped AppArmor userns permission for IDEA 2026.2.3 JBR.
# Never disable global userns/AppArmor restrictions or the JCEF sandbox.
set -euo pipefail
profile_body() {
  local root="$1"
  [[ "$root" == /home/runner/work/MarkFlow/MarkFlow ]] || {
    echo 'JCEF profile: workspace path is not the pinned runner location' >&2
    return 1
  }
  cat <<EOF
# Disposable CI equivalent of JetBrains' generated sandboxed JCEF profile.
abi <abi/4.0>,
include <tunables/global>
profile "$root/out/ide-tests/cache/builds/IU-262.10968.63/idea-IU-262.10968.63/jbr/bin/java" flags=(unconfined) {
  userns,
  include if exists <local/chrome>
}
EOF
}
if [[ "${1:-}" == --self-test ]]; then
  content="$(profile_body /home/runner/work/MarkFlow/MarkFlow)"
  [[ "$content" == *'flags=(unconfined) {'* && "$content" == *'  userns,'* ]]
  [[ "$content" == *'/out/ide-tests/cache/builds/IU-262.10968.63/idea-IU-262.10968.63/jbr/bin/java"'* ]]
  ! profile_body /tmp/untrusted >/dev/null 2>&1
  ! profile_body '/home/runner/work/MarkFlow/MarkFlow/**' >/dev/null 2>&1
  echo 'Pinned JBR AppArmor template self-test PASS'
  exit 0
fi
[[ "${1:-}" == "" && "${STARTER_SHARD:-}" == derived-content ]] || exit 1
[[ "${RUNNER_OS:-}" == Linux && "${RUNNER_ARCH:-}" == X64 ]] || exit 1
[[ "${STARTER_PLATFORM_VERSION:-}" == 2026.2.3 ]] || exit 1
[[ -f /sys/module/apparmor/parameters/enabled ]] &&
  [[ "$(cat /sys/module/apparmor/parameters/enabled)" == Y ]] || {
    echo 'AppArmor unavailable; fail closed' >&2; exit 1
  }
[[ -d /etc/apparmor.d && -x /usr/sbin/apparmor_parser ]] || {
  echo 'AppArmor parser unavailable; fail closed' >&2; exit 1
}
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
profile_body "${GITHUB_WORKSPACE:-}" > "$tmp"
target=/etc/apparmor.d/markflow-starter-jcef-2026-2-3
sudo -n install -m 0644 "$tmp" "$target"
sudo -n /usr/sbin/apparmor_parser -r "$target"
sudo -n /usr/sbin/apparmor_parser -N "$target" |
  grep -Fq '/out/ide-tests/cache/builds/IU-262.10968.63/idea-IU-262.10968.63/jbr/bin/java'
# Parsing does NOT prove the loaded profile actually exists in the kernel.
profiles=/sys/kernel/security/apparmor/profiles
[[ -r "$profiles" ]] || {
  echo 'Cannot read loaded AppArmor profile inventory; fail closed' >&2; exit 1
}
sudo -n cat "$profiles" |
  grep -Fq '/out/ide-tests/cache/builds/IU-262.10968.63/idea-IU-262.10968.63/jbr/bin/java' || {
    echo 'Pinned JBR AppArmor profile not confirmed in kernel inventory' >&2; exit 1
  }
echo 'Confirmed JBR-only userns AppArmor profile is loaded (JCEF sandbox still enabled)'
