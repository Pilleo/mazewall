#!/usr/bin/env bash
# Verify the packaged Linux UAPI headers against the reviewed snapshot.
set -u

readonly REVIEWED_BASELINE_VERSION="7.0.0-42.42"
readonly HEADERS=(seccomp.h landlock.h io_uring.h audit.h bpf_common.h)

install_headers=false
snapshot_dir=""
headers_dir=""
output_dir=""

usage() {
  echo "usage: $0 --install --snapshot-dir DIR --headers-dir DIR --output-dir DIR" >&2
  exit 2
}

while (($#)); do
  case "$1" in
    --install) install_headers=true ;;
    --snapshot-dir) snapshot_dir=${2:?missing value for --snapshot-dir}; shift ;;
    --headers-dir) headers_dir=${2:?missing value for --headers-dir}; shift ;;
    --output-dir) output_dir=${2:?missing value for --output-dir}; shift ;;
    *) usage ;;
  esac
  shift
done

[[ $install_headers == true && -n $snapshot_dir && -n $headers_dir && -n $output_dir ]] || usage
mkdir -p "$output_dir" || exit 2

report="$output_dir/report.txt"
printf 'reviewed-baseline-version=%s\n' "$REVIEWED_BASELINE_VERSION" > "$report"

fail_input() {
  printf 'kind=input-error\n' > "$output_dir/kind"
  : > "$output_dir/changed"
  printf '%s\n' "$*" | tee -a "$report" >&2
  exit 2
}

sudo_bin=${SUDO_BIN:-sudo}
apt_get_bin=${APT_GET_BIN:-apt-get}
dpkg_query_bin=${DPKG_QUERY_BIN:-dpkg-query}
dpkg_bin=${DPKG_BIN:-dpkg}
diff_bin=${DIFF_BIN:-diff}

for tool in "$sudo_bin" "$apt_get_bin" "$dpkg_query_bin" "$dpkg_bin" "$diff_bin"; do
  command -v "$tool" >/dev/null 2>&1 || fail_input "required tool unavailable: $tool"
done

record_preexisting_package_version() {
  local preexisting_version
  if preexisting_version=$("$dpkg_query_bin" -W '-f=${Version}' linux-libc-dev) && [[ -n $preexisting_version ]]; then
    printf '%s\n' "$preexisting_version" > "$output_dir/package-version"
    printf 'preexisting\n' > "$output_dir/package-version-source"
    printf 'preexisting-package-version=%s\n' "$preexisting_version" >> "$report"
  else
    printf 'unknown\n' > "$output_dir/package-version"
    printf 'unavailable\n' > "$output_dir/package-version-source"
    printf 'preexisting package version unavailable after installation failure\n' >> "$report"
  fi
}

if ! "$sudo_bin" "$apt_get_bin" update; then
  record_preexisting_package_version
  fail_input "linux-libc-dev update failed"
fi
if ! "$sudo_bin" "$apt_get_bin" install -y linux-libc-dev; then
  record_preexisting_package_version
  fail_input "linux-libc-dev header installation failed"
fi

package_version=$("$dpkg_query_bin" -W '-f=${Version}' linux-libc-dev) || fail_input "cannot query installed linux-libc-dev version"
[[ -n $package_version ]] || fail_input "installed linux-libc-dev version is empty"
printf '%s\n' "$package_version" > "$output_dir/package-version"
printf 'authoritative\n' > "$output_dir/package-version-source"
printf 'installed-package-version=%s\n' "$package_version" >> "$report"

"$dpkg_bin" --compare-versions "$package_version" ge "$REVIEWED_BASELINE_VERSION"
version_status=$?
case $version_status in
  0) ;;
  1) fail_input "installed linux-libc-dev $package_version is older than reviewed baseline $REVIEWED_BASELINE_VERSION" ;;
  *) fail_input "cannot compare installed linux-libc-dev version $package_version to reviewed baseline $REVIEWED_BASELINE_VERSION" ;;
esac

changed=()
for header in "${HEADERS[@]}"; do
  baseline="$snapshot_dir/$header"
  current="$headers_dir/$header"
  [[ -f $baseline && -r $baseline ]] || fail_input "missing or unreadable baseline header: $baseline"
  [[ -f $current && -r $current ]] || fail_input "missing or unreadable current header: $current"

  diff_file="$output_dir/$header.diff"
  "$diff_bin" -u "$baseline" "$current" > "$diff_file"
  diff_status=$?
  case $diff_status in
    0) rm -f "$diff_file" ;;
    1)
      changed+=("$header")
      printf 'uapi drift detected in %s\n' "$header" | tee -a "$report"
      cat "$diff_file" >> "$report" || fail_input "cannot retain diff for $header"
      ;;
    *) fail_input "diff failed for $header with exit code $diff_status" ;;
  esac
done

printf '%s\n' "${changed[*]}" > "$output_dir/changed"
if ((${#changed[@]})); then
  printf 'kind=drift\n' > "$output_dir/kind"
  exit 1
fi

printf 'kind=clean\n' > "$output_dir/kind"
