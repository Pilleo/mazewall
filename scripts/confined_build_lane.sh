#!/usr/bin/env bash
# Shared by the local build and pre-commit gate.

has_cap_checkpoint_restore() {
  local cap_eff
  cap_eff=$(awk '/^CapEff:/ {print $2}' /proc/self/status 2>/dev/null || true)
  [[ -n "$cap_eff" ]] && (( (16#$cap_eff & (1 << 40)) != 0 ))
}

confined_lane_blocker() {
  local root=$1 docker_security
  if [[ ! -x "$root/scripts/run_confined_helper_tests.sh" ]]; then
    echo "scripts/run_confined_helper_tests.sh is missing or not executable"
    return 0
  fi
  if [[ ! -f "$root/infra/dev/Containerfile" ]]; then
    echo "infra/dev/Containerfile is missing"
    return 0
  fi
  if [[ ! -f "$root/.git/index" ]]; then
    echo "linked git worktree lacks .git/index required by run_confined_helper_tests.sh"
    return 0
  fi
  if [[ "$(id -u)" -eq 0 ]]; then
    echo "caller-owned non-root UID required by run_confined_helper_tests.sh"
    return 0
  fi
  if ! [[ "$(cat /proc/self/uid_map 2>/dev/null || true)" =~ ^[[:space:]]*0[[:space:]]+0[[:space:]]+4294967295[[:space:]]*$ ]]; then
    echo "initial user namespace is required"
    return 0
  fi
  if ! command -v docker >/dev/null 2>&1; then
    echo "docker CLI not found"
    return 0
  fi
  if [[ ! -S /var/run/docker.sock ]]; then
    echo "rootful Docker socket /var/run/docker.sock not found"
    return 0
  fi
  if ! docker_security=$(docker --host unix:///var/run/docker.sock info --format '{{json .SecurityOptions}}' 2>/dev/null); then
    echo "cannot reach rootful Docker daemon at /var/run/docker.sock"
    return 0
  fi
  if printf '%s' "$docker_security" | grep -qi 'rootless'; then
    echo "Docker daemon at /var/run/docker.sock is rootless"
    return 0
  fi
  return 1
}

run_confined_build_lane() {
  local canonical_root containerfile_sha image_sha image_id project_key toolchain_key confined_gradle_home
  local -a docker_cmd=(docker --host unix:///var/run/docker.sock)
  canonical_root=$(cd "$1" && pwd -P)
  containerfile_sha=$(sha256sum "$canonical_root/infra/dev/Containerfile" | cut -d' ' -f1)
  image_sha=$("${docker_cmd[@]}" image inspect mazewall-test-runner --format '{{index .Config.Labels "io.mazewall.containerfile.sha256"}}' 2>/dev/null || true)
  if [[ "$image_sha" != "$containerfile_sha" ]]; then
    echo "==> Building mazewall-test-runner image for CONFINED kernel lane..."
    "${docker_cmd[@]}" build \
      --label "io.mazewall.containerfile.sha256=$containerfile_sha" \
      -t mazewall-test-runner -f "$canonical_root/infra/dev/Containerfile" "$canonical_root"
  fi
  image_id=$("${docker_cmd[@]}" image inspect --format '{{.Id}}' mazewall-test-runner)
  project_key=$(printf '%s' "$canonical_root" | sha256sum | cut -c1-12)
  toolchain_key=$(sha256sum "$canonical_root/gradle/wrapper/gradle-wrapper.properties" "$canonical_root/infra/dev/Containerfile" | sha256sum | cut -c1-12)
  confined_gradle_home="${XDG_CACHE_HOME:-$HOME/.cache}/mazewall/container-gradle/docker/$(id -u)/${project_key}/${toolchain_key}"
  mkdir -p "$confined_gradle_home"
  "$canonical_root/scripts/run_confined_helper_tests.sh" --image "$image_id" --gradle-home "$confined_gradle_home"
}
