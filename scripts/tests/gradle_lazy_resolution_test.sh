#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
scanner=${1:-"$root/scripts/check_gradle_lazy_resolution.sh"}
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT
failures=0
cases=0

# Each fixture is scanned by the production entrypoint from its own directory.
check() {
    local name=$1 expected=$2 extension=$3 status=0
    local diagnostic=${4:-'Eager Gradle configuration resolution: .*:[0-9]+:'}
    rm -f "$fixture/build.gradle" "$fixture/build.gradle.kts"
    cat > "$fixture/build.$extension"
    if [[ $extension == gradle ]]; then
        # Shared cases use Kotlin declaration/iteration spelling; retain equivalent
        # Groovy fixtures rather than relying on syntactically invalid scripts.
        sed -i -e 's/\bval\b/def/g' -e 's/listOf(1)\.forEach/[1].each/g' "$fixture/build.$extension"
    fi
    (cd "$fixture" && bash "$scanner") > "$fixture/output" 2>&1 || status=$?
    cases=$((cases + 1))
    if [[ $status != "$expected" ]]; then
        printf 'FAIL: %s expected %s got %s\n' "$name" "$expected" "$status"
        cat "$fixture/output"
        failures=$((failures + 1))
    elif [[ $expected == 1 ]] && ! rg -q "$diagnostic" "$fixture/output"; then
        printf 'FAIL: %s missing location diagnostic\n' "$name"
        failures=$((failures + 1))
    fi
}

for extension in gradle.kts gradle; do
    check 'multiline Spring doFirst' 0 "$extension" <<'EOF'
tasks.named("test") {
    doFirst {
        systemProperty("classpath", (classpath - testOutput).asPath)
    }
}
EOF
    check 'nested action and strings/comments' 0 "$extension" <<'EOF'
tasks.register("inspect") {
    doLast /* } */ (
        {
            listOf(1).forEach {
                println("} doFirst { .singleFile")
                println(configurations.runtimeClasspath.asPath)
            }
        }
    )
}
EOF
    check 'provider body' 0 "$extension" <<'EOF'
val path = providers
    .provider {
        configurations.runtimeClasspath.singleFile
    }
EOF
    check 'eager outside action' 1 "$extension" <<'EOF'
tasks.register("inspect") {
    doFirst { println("ok") }
    println(configurations.runtimeClasspath.asPath)
}
EOF
    check 'same-line exemption escape' 1 "$extension" <<'EOF'
doLast { println("ok") }; val eager = configurations.runtimeClasspath.singleFile
EOF
    check 'eager action argument' 1 "$extension" <<'EOF'
doFirst(configurations.runtimeClasspath.asPath) { println("later") }
EOF
    check 'eager provider argument' 1 "$extension" <<'EOF'
providers.provider(configurations.runtimeClasspath.singleFile)
EOF
    check 'provider immediately consumed during configuration' 1 "$extension" <<'EOF'
val eager = providers.provider { configurations.runtimeClasspath.asPath }.get()
EOF
    check 'parenthesized provider immediately consumed' 1 "$extension" <<'EOF'
val eager = providers.provider({ configurations.runtimeClasspath.asPath }).get()
EOF
    check 'action closure argument immediately invoked' 1 "$extension" <<'EOF'
doFirst({ configurations.runtimeClasspath.asPath }())
EOF
    check 'action closure invocation method remains eager' 1 "$extension" <<'EOF'
doFirst({ configurations.runtimeClasspath.asPath }.invoke())
EOF
    check 'configuration lambda stays eager' 1 "$extension" <<'EOF'
tasks.register("inspect") {
    listOf(1).forEach {
        println(configurations.runtimeClasspath.asPath)
    }
}
EOF
    check 'comment and literal doFirst do not exempt' 1 "$extension" <<'EOF'
val label = "doFirst {"
// doLast {
val eager = configurations.runtimeClasspath.asPath // providers.provider
EOF
    check 'strings and multiline comments do not resolve' 0 "$extension" <<'EOF'
/* configurations.runtimeClasspath.asPath
 * doFirst { } */
val sample = ".singleFile }"
val triple = """doLast { .asPath }"""
EOF
    check 'eager string interpolation' 1 "$extension" <<'EOF'
val eager = "classpath=${configurations.runtimeClasspath.asPath}"
EOF
    check 'deferred string interpolation' 0 "$extension" <<'EOF'
doFirst {
    println("classpath=${configurations.runtimeClasspath.asPath}")
}
EOF
    check 'configuration files property' 1 "$extension" <<'EOF'
val eager = configurations.runtimeClasspath.files
EOF
    check 'configuration files alias' 1 "$extension" <<'EOF'
val cp = configurations.getByName("runtimeClasspath")
val eager = cp.files
EOF
    check 'files registration is lazy' 0 "$extension" <<'EOF'
inputs.files(configurations.runtimeClasspath)
EOF
    check 'multiline configuration files receiver' 1 "$extension" <<'EOF'
val eager = configurations
    .getByName("runtimeClasspath")
    .files
EOF
    check 'configuration getFiles method' 1 "$extension" <<'EOF'
val eager = configurations.getByName("runtimeClasspath").getFiles()
EOF
    check 'action bracket does not leak past literal brace' 1 "$extension" <<'EOF'
doFirst { println("{ /* doLast") }
val eager = configurations.runtimeClasspath.asPath
EOF
    check 'literal close brace does not terminate action' 0 "$extension" <<'EOF'
doLast {
    println("}")
    println(configurations.runtimeClasspath.singleFile)
}
EOF
    check 'triple string interpolates eagerly' 1 "$extension" <<'EOF'
val eager = """classpath=${configurations.runtimeClasspath.singleFile}"""
EOF
    check 'interpolation contains nested quotes and braces' 0 "$extension" <<'EOF'
doLast {
    println("cp=${listOf("}").map { configurations.runtimeClasspath.asPath }}")
}
EOF
done

check 'Kotlin TaskAction nested body' 0 gradle.kts <<'EOF'
abstract class Inspect : DefaultTask() {
    @TaskAction
    fun inspect() {
        listOf(1).forEach { println(configurations.runtimeClasspath.singleFile) }
    }
}
EOF
check 'TaskAction does not exempt neighboring initializer' 1 gradle.kts <<'EOF'
abstract class Inspect : DefaultTask() {
    @TaskAction fun inspect() { println("later") }
    val eager = configurations.runtimeClasspath.asPath
}
EOF
check 'Kotlin argument provider expression' 0 gradle.kts <<'EOF'
abstract class Args : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf("cp=" + configurations.runtimeClasspath.asPath)
}
EOF
check 'asArguments call does not exempt initializer' 1 gradle.kts <<'EOF'
val eager = asArguments() + configurations.runtimeClasspath.asPath
EOF
check 'TaskAction parameter defaults remain eager' 1 gradle.kts <<'EOF'
class Inspect : DefaultTask() {
    @TaskAction
    fun inspect(path: String = configurations.runtimeClasspath.asPath) { println(path) }
}
EOF
check 'asArguments expression does not exempt next initializer' 1 gradle.kts <<'EOF'
class Args : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("ok")
    val eager = configurations.runtimeClasspath.asPath
}
EOF
check 'Kotlin nested multiline comments' 0 gradle.kts <<'EOF'
/* outer { /* doLast .asPath */ .singleFile } */
doFirst { println(configurations.runtimeClasspath.asPath) }
EOF
check 'TaskAction named in string does not exempt' 1 gradle.kts <<'EOF'
val note = "@TaskAction"
fun configure() { println(configurations.runtimeClasspath.singleFile) }
EOF
check 'Groovy TaskAction method' 0 gradle <<'EOF'
class Inspect extends DefaultTask {
    @org.gradle.api.tasks.TaskAction
    void inspect() {
        println(configurations.runtimeClasspath.asPath)
    }
}
EOF
check 'Groovy argument provider method' 0 gradle <<'EOF'
class Args implements CommandLineArgumentProvider {
    @Override
    Iterable<String> asArguments() {
        return [configurations.runtimeClasspath.asPath]
    }
}
EOF
check 'Groovy slashy literal braces' 0 gradle <<'EOF'
val pattern = /doFirst { .asPath }/
val dollarSlashy = $/doLast { .singleFile }/$
EOF
check 'Groovy shorthand interpolation resolves eagerly' 1 gradle <<'EOF'
def eager = "$configurations.runtimeClasspath.asPath"
EOF
check 'Groovy asArguments call with eager map stays eager' 1 gradle <<'EOF'
def configure() {
    return asArguments().collect { configurations.runtimeClasspath.asPath }
}
EOF
check 'unbalanced scopes fail closed' 1 gradle.kts 'Gradle resolution scan failed:' <<'EOF'
doFirst { println(configurations.runtimeClasspath.asPath)
EOF
check 'unterminated literal fails closed' 1 gradle 'Gradle resolution scan failed:' <<'EOF'
def note = "doLast { .asPath
EOF
check 'actual Spring script action' 0 gradle.kts < "$root/integrations/spring/mazewall-spring-boot/build.gradle.kts"
check 'actual Java API consumer eager args' 1 gradle < "$root/tests/consumers/java-api/build.gradle"

if [[ $failures != 0 ]]; then
    printf 'FAIL: %s/%s scanner cases\n' "$failures" "$cases"
    exit 1
fi
printf 'PASS: %s actual-scanner fixtures\n' "$cases"
