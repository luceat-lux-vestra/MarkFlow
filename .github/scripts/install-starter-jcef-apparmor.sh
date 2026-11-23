#!/usr/bin/env bash
# CI-only scoped AppArmor userns permission for the exact IDEA executable resolved by Starter.
# Never disable global userns/AppArmor restrictions or the JCEF sandbox.
set -euo pipefail

profile_list_contains_exact() {
  local expected="$1"
  local line suffix mode
  while IFS= read -r line || [[ -n "$line" ]]; do
    if [[ "$line" == "$expected" ]]; then
      return 0
    fi
    if [[ "$line" == "$expected ("* && "$line" == *")" ]]; then
      suffix="${line#"$expected ("}"
      mode="${suffix%)}"
      if [[ -n "$mode" && "$mode" != *'('* && "$mode" != *')'* &&
            "$line" == "$expected ($mode)" ]]; then
        return 0
      fi
    fi
  done
  return 1
}

profile_body() {
  local executable="$1"
  [[ "$executable" =~ ^/[A-Za-z0-9._/+:-]+$ ]] || {
    echo 'JCEF profile: executable path is not a safe canonical absolute path' >&2
    return 1
  }
  [[ "$executable" != *'*'* && "$executable" != *'?'* && "$executable" != *'['* ]] || {
    echo 'JCEF profile: wildcard executable paths are forbidden' >&2
    return 1
  }
  cat <<EOF
# Disposable CI equivalent of JetBrains' generated sandboxed JCEF profile.
abi <abi/4.0>,
include <tunables/global>
profile "$executable" flags=(unconfined) {
  userns,
  include if exists <local/chrome>
}
EOF
}

read_metadata() {
  local metadata="$1"
  [[ -f "$metadata" ]] || {
    echo "Starter IDE metadata is missing: $metadata" >&2
    return 1
  }

  local line key value
  platform_version=''
  resolved_build=''
  installation_path=''
  executable_path=''
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" == *=* ]] || {
      echo 'Starter IDE metadata contains a malformed line' >&2
      return 1
    }
    key="${line%%=*}"
    value="${line#*=}"
    [[ -n "$value" ]] || {
      echo "Starter IDE metadata value is blank: $key" >&2
      return 1
    }
    case "$key" in
      platform_version)
        [[ -z "$platform_version" ]] || return 1
        platform_version="$value"
        ;;
      resolved_build)
        [[ -z "$resolved_build" ]] || return 1
        resolved_build="$value"
        ;;
      installation_path)
        [[ -z "$installation_path" ]] || return 1
        installation_path="$value"
        ;;
      executable_path)
        [[ -z "$executable_path" ]] || return 1
        executable_path="$value"
        ;;
      *)
        echo "Starter IDE metadata contains an unknown key: $key" >&2
        return 1
        ;;
    esac
  done < "$metadata"

  [[ -n "$platform_version" && -n "$resolved_build" &&
     -n "$installation_path" && -n "$executable_path" ]] || {
    echo 'Starter IDE metadata is incomplete' >&2
    return 1
  }
}

if [[ "${1:-}" == --self-test ]]; then
  sample='/workspace/out/ide-tests/cache/builds/IU-example/idea-IU-example/bin/idea'
  content="$(profile_body "$sample")"
  [[ "$content" == *'flags=(unconfined) {'* && "$content" == *'  userns,'* ]]
  [[ "$content" == *"$sample"* ]]
  printf '%s\n' "$sample" | profile_list_contains_exact "$sample"
  printf '%s\n' "$sample (unconfined)" | profile_list_contains_exact "$sample"
  ! printf '%s\n' "$sample-extra (unconfined)" | profile_list_contains_exact "$sample"
  ! printf '%s\n' "$sample ()" | profile_list_contains_exact "$sample"
  ! printf '%s\n' "$sample (unconfined) trailing" | profile_list_contains_exact "$sample"
  ! printf '%s\n' "$sample (unconfined)(shadow)" | profile_list_contains_exact "$sample"
  ! profile_body 'relative/bin/idea' >/dev/null 2>&1
  ! profile_body '/workspace/**/bin/idea' >/dev/null 2>&1

  metadata="$(mktemp)"
  trap 'rm -f "$metadata"' EXIT
  cat > "$metadata" <<EOF
platform_version=example-release
resolved_build=example-build
installation_path=/workspace/out/ide-tests/cache/builds/IU-example/idea-IU-example
executable_path=$sample
EOF
  read_metadata "$metadata"
  [[ "$platform_version" == example-release ]]
  [[ "$resolved_build" == example-build ]]
  [[ "$executable_path" == "$sample" ]]
  echo 'Starter-resolved IDEA executable AppArmor template self-test PASS'
  exit 0
fi

[[ $# -eq 1 ]] || {
  echo 'usage: install-starter-jcef-apparmor.sh <starter-ide-metadata>' >&2
  exit 1
}
case "${STARTER_SHARD:-}" in
  derived-content|full-differential-acceptance) ;;
  *) exit 1 ;;
esac
[[ "${RUNNER_OS:-}" == Linux && "${RUNNER_ARCH:-}" == ARM64 ]] || exit 1
[[ -n "${STARTER_PLATFORM_VERSION:-}" ]] || exit 1
[[ -n "${GITHUB_WORKSPACE:-}" ]] || exit 1

read_metadata "$1"
[[ "$platform_version" == "$STARTER_PLATFORM_VERSION" ]] || {
  echo 'Starter IDE metadata platform does not match the requested platform' >&2
  exit 1
}

workspace="$(realpath -e -- "$GITHUB_WORKSPACE")"
installation="$(realpath -e -- "$installation_path")"
executable="$(realpath -e -- "$executable_path")"
[[ -d "$installation" && -f "$executable" && -x "$executable" ]] || {
  echo 'Starter-resolved IDEA installation/executable is unavailable' >&2
  exit 1
}
[[ "$installation" == "$workspace/"* ]] || {
  echo 'Starter-resolved IDEA installation is outside the CI workspace; fail closed' >&2
  exit 1
}
[[ "$executable" == "$installation/"* ]] || {
  echo 'Starter-resolved IDEA executable escaped the resolved installation; fail closed' >&2
  exit 1
}

[[ -f /sys/module/apparmor/parameters/enabled ]] &&
  [[ "$(cat /sys/module/apparmor/parameters/enabled)" == Y ]] || {
    echo 'AppArmor unavailable; fail closed' >&2
    exit 1
  }
[[ -d /etc/apparmor.d && -x /usr/sbin/apparmor_parser ]] || {
  echo 'AppArmor parser unavailable; fail closed' >&2
  exit 1
}

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
profile_body "$executable" > "$tmp"
target=/etc/apparmor.d/markflow-starter-jcef
sudo -n install -m 0644 "$tmp" "$target"
sudo -n /usr/sbin/apparmor_parser -r "$target"
sudo -n /usr/sbin/apparmor_parser -N "$target" |
  profile_list_contains_exact "$executable"

# Parsing does not prove the loaded profile actually exists in the kernel.
profiles=/sys/kernel/security/apparmor/profiles
[[ -r "$profiles" ]] || {
  echo 'Cannot read loaded AppArmor profile inventory; fail closed' >&2
  exit 1
}
sudo -n cat "$profiles" | profile_list_contains_exact "$executable" || {
  echo 'Starter-resolved IDEA AppArmor profile not confirmed exactly in kernel inventory' >&2
  exit 1
}
echo "Confirmed exact Starter IDEA executable userns AppArmor profile: build=$resolved_build"
