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
    elif [[ $expected == 0 && -s "$fixture/output" ]]; then
        printf 'FAIL: %s unexpected scanner output\n' "$name"
        cat "$fixture/output"
        failures=$((failures + 1))
    elif [[ $expected == 1 && $diagnostic == @* ]]; then
        # Locations are specified by the fixture, independently of the scanner.
        # Compare the complete diagnostic inventory, including offending text.
        local line text
        local -a lines
        IFS=, read -r -a lines <<< "${diagnostic#@}"
        : > "$fixture/expected"
        for line in "${lines[@]}"; do
            text=$(sed -n "${line}p" "$fixture/build.$extension" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
            printf 'Eager Gradle configuration resolution: build.%s:%s: %s\n' "$extension" "$line" "$text" >> "$fixture/expected"
        done
        if ! diff -u "$fixture/expected" "$fixture/output"; then
            printf 'FAIL: %s diagnostic location/text/count mismatch\n' "$name"
            failures=$((failures + 1))
        fi
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
    check 'eager outside action' 1 "$extension" @3 <<'EOF'
tasks.register("inspect") {
    doFirst { println("ok") }
    println(configurations.runtimeClasspath.asPath)
}
EOF
    check 'same-line exemption escape' 1 "$extension" @1 <<'EOF'
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
    check 'I1 grouped trailing provider immediately consumed' 1 "$extension" @2 <<'EOF'
val eager = ((providers.provider {
    configurations.runtimeClasspath.asPath
})).get()
EOF
    check 'I1 grouped lambda argument immediately consumed' 1 "$extension" @3 <<'EOF'
val eager = (providers.provider(
    {
        configurations.runtimeClasspath.singleFile
    }
)).get()
EOF
    check 'I1 deeply grouped provider nullable consumption' 1 "$extension" @2 <<'EOF'
val eager = (((providers.provider {
    configurations.runtimeClasspath.asPath
}))).orNull
EOF
    check 'I1 unconsumed grouped trailing provider' 0 "$extension" <<'EOF'
val deferred = ((providers.provider {
    configurations.runtimeClasspath.asPath
}))
EOF
    check 'I1 unconsumed grouped lambda argument' 0 "$extension" <<'EOF'
val deferred = (providers.provider({ configurations.runtimeClasspath.singleFile }))
EOF
    check 'I1 immediate provider get inside action is legitimate' 0 "$extension" <<'EOF'
doFirst {
    val path = ((providers.provider { configurations.runtimeClasspath.asPath })).get()
}
EOF
    check 'action closure argument immediately invoked' 1 "$extension" <<'EOF'
doFirst({ configurations.runtimeClasspath.asPath }())
EOF
    check 'action closure invocation method remains eager' 1 "$extension" <<'EOF'
doFirst({ configurations.runtimeClasspath.asPath }.invoke())
EOF
    check 'configuration lambda stays eager' 1 "$extension" @3 <<'EOF'
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
    check 'multiline configuration files receiver' 1 "$extension" @3 <<'EOF'
val eager = configurations
    .getByName("runtimeClasspath")
    .files
EOF
    check 'configuration getFiles method' 1 "$extension" <<'EOF'
val eager = configurations.getByName("runtimeClasspath").getFiles()
EOF
    check 'I2 indexed configuration files' 1 "$extension" @1 <<'EOF'
val eager = configurations["runtimeClasspath"].files
EOF
    check 'I2 multiline indexed configuration getFiles' 1 "$extension" @3 <<'EOF'
val eager = configurations[
    "runtimeClasspath"
].getFiles()
EOF
    check 'I2 indexed configuration asPath stays forbidden' 1 "$extension" @1 <<'EOF'
val eager = configurations["runtimeClasspath"].asPath
EOF
    check 'I2 multiline indexed files stays forbidden' 1 "$extension" @3 <<'EOF'
val eager = configurations[
    "runtimeClasspath"
].files
EOF
    check 'I2 indexed configuration action consumption' 0 "$extension" <<'EOF'
doLast {
    println(configurations["runtimeClasspath"].files)
    println(configurations["runtimeClasspath"].getFiles())
    println(configurations["runtimeClasspath"].asPath)
}
EOF
    check 'I2 indexed configuration input registration' 0 "$extension" <<'EOF'
inputs.files(configurations["runtimeClasspath"])
EOF
    check 'M2 exact multi-location inventory outside actions' 1 "$extension" @2,4 <<'EOF'
doFirst { println(configurations["runtimeClasspath"].asPath) }
val eager = configurations["runtimeClasspath"].files
doLast { println(configurations["runtimeClasspath"].getFiles()) }
val eagerAgain = configurations["runtimeClasspath"].singleFile
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

check 'I1 enclosing list get is not provider consumption' 0 gradle.kts <<'EOF'
val deferred = listOf(providers.provider {
    configurations.runtimeClasspath.asPath
}).get(0)
EOF
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
check 'M1 multiline Groovy slashy literal braces' 0 gradle <<'EOF'
def pattern =
    /literal } .asPath doFirst {/
EOF
check 'M1 multiline Groovy slashy interpolation remains eager' 1 gradle @2 <<'EOF'
def pattern =
    /classpath=${configurations["runtimeClasspath"].asPath}/
EOF
check 'M1 Groovy multiline division remains code' 1 gradle @3 <<'EOF'
def ratio = 12
    / 3
def eager = configurations["runtimeClasspath"].asPath
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
check 'actual Java API consumer execution-phase args' 0 gradle < "$root/tests/consumers/java-api/build.gradle"
check 'original Java API consumer eager args remain forbidden' 1 gradle @17 <<'EOF'
plugins { id 'application' }

java { toolchain { languageVersion = JavaLanguageVersion.of(providers.gradleProperty('consumerJdk').getOrElse('25').toInteger()) } }

dependencies {
    implementation "io.mazewall:enforcer:${providers.gradleProperty('consumerVersion').get()}"
}

application { mainClass = 'consumer.JavaApiConsumer' }

['verifyCallableEntry': 'callable', 'verifyScopeBoundaries': 'scope'].each { taskName, fixture ->
    tasks.register(taskName, JavaExec) {
        dependsOn tasks.named('classes')
        javaLauncher = javaToolchains.launcherFor(java.toolchain)
        classpath = sourceSets.main.runtimeClasspath
        mainClass = 'consumer.VerifyCompilation'
        args fixture, sourceSets.main.compileClasspath.asPath,
            file("src/${fixture}/java/consumer").absolutePath,
            layout.buildDirectory.dir(fixture).get().asFile.absolutePath
    }
}
EOF

if [[ $failures != 0 ]]; then
    printf 'FAIL: %s/%s scanner cases\n' "$failures" "$cases"
    exit 1
fi
printf 'PASS: %s actual-scanner fixtures\n' "$cases"
