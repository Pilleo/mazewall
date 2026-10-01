#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
repository=$(cd "$root/../.." && pwd)
cd "$root"
docker_cmd=("${DOCKER:-docker}")
if [[ -n "${COLLECTOR_DOCKER_HOST:-}" ]]; then docker_cmd+=(--host "$COLLECTOR_DOCKER_HOST"); fi
jdk_image=$(python3 -c 'import json;print(json.load(open("dependencies.lock.json"))["images"]["jdk"])')
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
python3 - "$artifact" "$jdk_image" <<'PY'
import hashlib,json,os,pathlib,platform,sys
root=pathlib.Path(sys.argv[1])
data={"jdk_image":sys.argv[2],"target_uid":os.getuid(),"kernel":platform.release(),"reference":"USER_NOTIF","independent_jvm":True,"source_sha256":hashlib.sha256(pathlib.Path("fixtures/java/InlineReferenceFixture.java").read_bytes()).hexdigest(),"class_sha256":hashlib.sha256(pathlib.Path("build/reference-classes/InlineReferenceFixture.class").read_bytes()).hexdigest()}
(root/"reference-environment.json").write_text(json.dumps(data,indent=2)+"\n")
PY
printf 'Independent reference artifacts: %s\n' "$artifact"
