#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
repository=$(cd "$root/../.." && pwd)
cd "$root"
docker_cmd=("${DOCKER:-docker}")
if [[ -n "${COLLECTOR_DOCKER_HOST:-}" ]]; then docker_cmd+=(--host "$COLLECTOR_DOCKER_HOST"); fi
jdk_image=$("${GO:-go}" run ./cmd/mw-collector-tools image --key jdk)
make experiment-tools
# Export the already pinned JDK for an ordinary-user host reference process.
# No application-container privilege or host security setting is changed.
jdk="$root/build/reference-jdk-${jdk_image##*@sha256:}"
if [[ ! -x "$jdk/bin/java" ]]; then
    mkdir -p "$jdk"
    "${docker_cmd[@]}" run --rm --user "$UID:$(id -g)" \
        --mount "type=bind,src=$jdk,dst=/out" "$jdk_image" \
        cp -R /opt/java/openjdk/. /out/
fi
"$repository/gradlew" --project-dir "$repository" --no-configuration-cache \
    -I "$root/scripts/reference.gradle" :profiler:agentlessReferenceClasspath
classpath=$(cat build/reference-classpath.txt)
mkdir -p build/reference-classes
"$jdk/bin/javac" -g -cp "$classpath" -d build/reference-classes fixtures/java/InlineReferenceFixture.java
artifact=$(mktemp -d "$root/build/reference.XXXXXX")
"$jdk/bin/java" -version > "$artifact/jdk-version.txt" 2>&1
timeout 120s "$jdk/bin/java" --enable-native-access=ALL-UNNAMED \
    -cp "$root/build/reference-classes:$classpath" InlineReferenceFixture "$artifact" 1000 \
    > "$artifact/reference.log" 2>&1
build/mw-experiment-tools reference-environment "$artifact" "$jdk_image"
printf 'Independent reference artifacts: %s\n' "$artifact"
