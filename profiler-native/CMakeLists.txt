cmake_minimum_required(VERSION 3.20)
project(mazewall_stack_agent LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(JNI REQUIRED)
find_package(Java REQUIRED COMPONENTS Development)

add_library(mazewall_stack_agent SHARED src/agent.cpp)
target_include_directories(mazewall_stack_agent PRIVATE include ${JNI_INCLUDE_DIRS})
target_compile_options(mazewall_stack_agent PRIVATE -Wall -Wextra -Werror)
set_target_properties(mazewall_stack_agent PROPERTIES OUTPUT_NAME mazewall_stack_agent)

enable_testing()
add_executable(invocation_registry_test tests/invocation_registry_test.cpp)
target_include_directories(invocation_registry_test PRIVATE include)
target_compile_options(invocation_registry_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_test(NAME invocation_registry_test COMMAND invocation_registry_test)

add_executable(binding_manifest_test tests/binding_manifest_test.cpp)
target_include_directories(binding_manifest_test PRIVATE include)
target_compile_options(binding_manifest_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_test(NAME binding_manifest_test COMMAND binding_manifest_test)

add_executable(capture_yield_recorder_test tests/capture_yield_recorder_test.cpp)
target_include_directories(capture_yield_recorder_test PRIVATE include)
target_compile_options(capture_yield_recorder_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_test(NAME capture_yield_recorder_test COMMAND capture_yield_recorder_test)

add_executable(capture_yield_file_test tests/capture_yield_file_test.cpp)
target_include_directories(capture_yield_file_test PRIVATE include)
target_compile_options(capture_yield_file_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)

add_executable(marker_export_test tests/marker_export_test.cpp)
target_compile_options(marker_export_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
target_link_libraries(marker_export_test PRIVATE ${CMAKE_DL_LIBS})
add_test(NAME marker_export_test COMMAND marker_export_test $<TARGET_FILE:mazewall_stack_agent>)

add_executable(seccomp_boundary_fixture tests/seccomp_boundary_fixture.cpp)
target_compile_options(seccomp_boundary_fixture PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_test(NAME seccomp_boundary_fixture COMMAND seccomp_boundary_fixture)

add_executable(symbolization_generation_test tests/symbolization_generation_test.cpp)
target_include_directories(symbolization_generation_test PRIVATE include)
target_compile_options(symbolization_generation_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_test(NAME symbolization_generation_test COMMAND symbolization_generation_test)

add_executable(lifecycle_identity_fixture tests/lifecycle_identity_fixture.cpp)
target_compile_options(lifecycle_identity_fixture PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_test(NAME lifecycle_identity_fixture COMMAND lifecycle_identity_fixture)

add_executable(lifecycle_identity_stress_fixture tests/lifecycle_identity_stress_fixture.cpp)
target_compile_options(lifecycle_identity_stress_fixture PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_test(NAME lifecycle_identity_stress_fixture COMMAND lifecycle_identity_stress_fixture)

set(AGENT_TEST_CLASSES "${CMAKE_CURRENT_BINARY_DIR}/agent-test-classes")
add_custom_command(
    OUTPUT "${AGENT_TEST_CLASSES}/AgentWorkload.class"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${AGENT_TEST_CLASSES}"
    COMMAND "${Java_JAVAC_EXECUTABLE}" -d "${AGENT_TEST_CLASSES}" "${CMAKE_CURRENT_SOURCE_DIR}/tests/AgentWorkload.java"
    DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/tests/AgentWorkload.java"
    VERBATIM
)
add_custom_command(
    OUTPUT "${AGENT_TEST_CLASSES}/RedefinitionWorkload.class" "${AGENT_TEST_CLASSES}/RedefinitionAgent.class"
    COMMAND "${Java_JAVAC_EXECUTABLE}" -d "${AGENT_TEST_CLASSES}" "${CMAKE_CURRENT_SOURCE_DIR}/tests/RedefinitionWorkload.java" "${CMAKE_CURRENT_SOURCE_DIR}/tests/RedefinitionAgent.java"
    DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/tests/RedefinitionWorkload.java" "${CMAKE_CURRENT_SOURCE_DIR}/tests/RedefinitionAgent.java"
    VERBATIM
)
add_custom_command(
    OUTPUT "${AGENT_TEST_CLASSES}/redefinition-agent.jar"
    COMMAND "${Java_JAR_EXECUTABLE}" cfm "${AGENT_TEST_CLASSES}/redefinition-agent.jar" "${CMAKE_CURRENT_SOURCE_DIR}/tests/redefinition-agent.mf" -C "${AGENT_TEST_CLASSES}" RedefinitionAgent.class
    DEPENDS "${AGENT_TEST_CLASSES}/RedefinitionAgent.class" "${CMAKE_CURRENT_SOURCE_DIR}/tests/redefinition-agent.mf"
    VERBATIM
)
add_custom_target(agent_workload_test_classes ALL DEPENDS "${AGENT_TEST_CLASSES}/AgentWorkload.class" "${AGENT_TEST_CLASSES}/redefinition-agent.jar")
add_test(
    NAME agent_smoke_test
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>" -cp "${AGENT_TEST_CLASSES}" AgentWorkload
)
set_tests_properties(
    agent_smoke_test
    PROPERTIES PASS_REGULAR_EXPRESSION "proxied-binds=[1-9][0-9]* stack-captures=[1-9][0-9]*"
)

add_test(
    NAME agent_phase_benchmark_full
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,mode=full-stream,definitions=${CMAKE_CURRENT_BINARY_DIR}/tier-e-benchmark-dictionary-full.mzsd" -cp "${AGENT_TEST_CLASSES}" AgentWorkload 0 platform 100
)
set_tests_properties(agent_phase_benchmark_full PROPERTIES PASS_REGULAR_EXPRESSION "tier-e-benchmark: stack_transport=dictionary get_stack_trace_ns=[1-9][0-9]* raw_key_ns=[1-9][0-9]* logical_metadata_ns=[1-9][0-9]* serialization_ns=[1-9][0-9]* publication_bytes=[1-9][0-9]*")

add_test(
    NAME agent_capture_profile_jdk25_openat
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,capture-profile=jdk25-unix-openat0" -cp "${AGENT_TEST_CLASSES}" AgentWorkload 0 platform 10
)
set_tests_properties(agent_capture_profile_jdk25_openat PROPERTIES PASS_REGULAR_EXPRESSION "mazewall-stack-agent:.*stack-captures=[1-9][0-9]*")

add_test(
    NAME agent_capture_yield_sidecar
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,capture-yield=${CMAKE_CURRENT_BINARY_DIR}/tier-e-capture-yield.mzcy" -cp "${AGENT_TEST_CLASSES}" AgentWorkload 0 platform 10
)
set_tests_properties(agent_capture_yield_sidecar PROPERTIES PASS_REGULAR_EXPRESSION "capture-yield=written")
add_test(
    NAME capture_yield_file_test
    COMMAND capture_yield_file_test "${CMAKE_CURRENT_BINARY_DIR}/tier-e-capture-yield.mzcy"
)
set_tests_properties(capture_yield_file_test PROPERTIES DEPENDS agent_capture_yield_sidecar)

add_test(
    NAME agent_phase_benchmark_unique
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,mode=unique-stack-syscall,definitions=${CMAKE_CURRENT_BINARY_DIR}/tier-e-benchmark-dictionary-unique.mzsd" -cp "${AGENT_TEST_CLASSES}" AgentWorkload 0 platform 100
)
set_tests_properties(agent_phase_benchmark_unique PROPERTIES PASS_REGULAR_EXPRESSION "tier-e-benchmark: stack_transport=dictionary get_stack_trace_ns=[1-9][0-9]* raw_key_ns=[1-9][0-9]* logical_metadata_ns=[1-9][0-9]* serialization_ns=[1-9][0-9]* publication_bytes=[1-9][0-9]*")

add_test(
    NAME agent_phase_benchmark_full_disabled
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,mode=full-stream" -cp "${AGENT_TEST_CLASSES}" AgentWorkload 0 platform 100
)
set_tests_properties(agent_phase_benchmark_full_disabled PROPERTIES PASS_REGULAR_EXPRESSION "tier-e-benchmark: stack_transport=dictionary get_stack_trace_ns=[1-9][0-9]* raw_key_ns=[1-9][0-9]* logical_metadata_ns=[1-9][0-9]*")

add_test(
    NAME agent_phase_benchmark_unique_disabled
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,mode=unique-stack-syscall" -cp "${AGENT_TEST_CLASSES}" AgentWorkload 0 platform 100
)
set_tests_properties(agent_phase_benchmark_unique_disabled PROPERTIES PASS_REGULAR_EXPRESSION "tier-e-benchmark: stack_transport=dictionary get_stack_trace_ns=[1-9][0-9]* raw_key_ns=[1-9][0-9]* logical_metadata_ns=[1-9][0-9]*")

add_test(
    NAME agent_phase_benchmark_full_live
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,mode=full-stream,live-definitions,definitions=${CMAKE_CURRENT_BINARY_DIR}/tier-e-benchmark-dictionary-full-live.mzsd" -cp "${AGENT_TEST_CLASSES}" AgentWorkload 0 platform 100
)
set_tests_properties(agent_phase_benchmark_full_live PROPERTIES PASS_REGULAR_EXPRESSION "tier-e-benchmark: stack_transport=dictionary get_stack_trace_ns=[1-9][0-9]* raw_key_ns=[1-9][0-9]* logical_metadata_ns=[1-9][0-9]* serialization_ns=[1-9][0-9]* publication_bytes=[1-9][0-9]*")

add_test(
    NAME agent_phase_benchmark_unique_live
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,mode=unique-stack-syscall,live-definitions,definitions=${CMAKE_CURRENT_BINARY_DIR}/tier-e-benchmark-dictionary-unique-live.mzsd" -cp "${AGENT_TEST_CLASSES}" AgentWorkload 0 platform 100
)
set_tests_properties(agent_phase_benchmark_unique_live PROPERTIES PASS_REGULAR_EXPRESSION "tier-e-benchmark: stack_transport=dictionary get_stack_trace_ns=[1-9][0-9]* raw_key_ns=[1-9][0-9]* logical_metadata_ns=[1-9][0-9]* serialization_ns=[1-9][0-9]* publication_bytes=[1-9][0-9]*")

add_test(
    NAME agent_live_publication_coalescing
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,mode=full-stream,live-definitions,definitions=${CMAKE_CURRENT_BINARY_DIR}/tier-e-live-coalescing.mzsd" -cp "${AGENT_TEST_CLASSES}" AgentWorkload 0 platform 1000
)
set_tests_properties(agent_live_publication_coalescing PROPERTIES PASS_REGULAR_EXPRESSION "publication_count=[1-9][0-9]* coalesced_publications=[1-9][0-9]* failed_publications=0")

add_test(
    NAME agent_matched_benchmark_none
    COMMAND "${Java_JAVA_EXECUTABLE}" -cp "${AGENT_TEST_CLASSES}" AgentWorkload benchmark 3 10 100
)
set_tests_properties(agent_matched_benchmark_none PROPERTIES PASS_REGULAR_EXPRESSION "agent-workload-batch: index=10 durationNs=[1-9][0-9]*")

add_test(
    NAME agent_matched_benchmark_dictionary_full
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,mode=full-stream,definitions=${CMAKE_CURRENT_BINARY_DIR}/tier-e-matched-dictionary-full.mzsd" -cp "${AGENT_TEST_CLASSES}" AgentWorkload benchmark 3 10 100
)
set_tests_properties(agent_matched_benchmark_dictionary_full PROPERTIES PASS_REGULAR_EXPRESSION "agent-workload-batch: index=10 durationNs=[1-9][0-9]*")

add_test(
    NAME agent_matched_benchmark_dictionary_unique
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,mode=unique-stack-syscall,definitions=${CMAKE_CURRENT_BINARY_DIR}/tier-e-matched-dictionary-unique.mzsd" -cp "${AGENT_TEST_CLASSES}" AgentWorkload benchmark 3 10 100
)
set_tests_properties(agent_matched_benchmark_dictionary_unique PROPERTIES PASS_REGULAR_EXPRESSION "agent-workload-batch: index=10 durationNs=[1-9][0-9]*")

add_test(
    NAME agent_matched_benchmark_inline_full
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,mode=full-stream,stack-transport=inline-per-invocation,definitions=${CMAKE_CURRENT_BINARY_DIR}/tier-e-matched-inline-full.mzsd" -cp "${AGENT_TEST_CLASSES}" AgentWorkload benchmark 3 10 100
)
set_tests_properties(agent_matched_benchmark_inline_full PROPERTIES PASS_REGULAR_EXPRESSION "agent-workload-batch: index=10 durationNs=[1-9][0-9]*")

add_test(
    NAME agent_matched_benchmark_inline_unique
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,mode=unique-stack-syscall,stack-transport=inline-per-invocation,definitions=${CMAKE_CURRENT_BINARY_DIR}/tier-e-matched-inline-unique.mzsd" -cp "${AGENT_TEST_CLASSES}" AgentWorkload benchmark 3 10 100
)
set_tests_properties(agent_matched_benchmark_inline_unique PROPERTIES PASS_REGULAR_EXPRESSION "agent-workload-batch: index=10 durationNs=[1-9][0-9]*")

add_test(
    NAME agent_native_retention_exhaustion
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,retained-budget-bytes=1,definitions=${CMAKE_CURRENT_BINARY_DIR}/tier-e-retention-exhaustion.mzsd" -cp "${AGENT_TEST_CLASSES}" AgentWorkload 0 platform 1
)
set_tests_properties(agent_native_retention_exhaustion PROPERTIES PASS_REGULAR_EXPRESSION "native_retention_exhaustions=[1-9][0-9]*")

add_test(
    NAME agent_phase_benchmark_inline_full
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,mode=full-stream,stack-transport=inline-per-invocation,definitions=${CMAKE_CURRENT_BINARY_DIR}/tier-e-benchmark-inline-full.mzsd" -cp "${AGENT_TEST_CLASSES}" AgentWorkload 0 platform 100
)
set_tests_properties(agent_phase_benchmark_inline_full PROPERTIES PASS_REGULAR_EXPRESSION "tier-e-benchmark: stack_transport=inline-per-invocation get_stack_trace_ns=[1-9][0-9]* raw_key_ns=[1-9][0-9]* logical_metadata_ns=[1-9][0-9]* serialization_ns=[1-9][0-9]* publication_bytes=[1-9][0-9]*")

add_test(
    NAME agent_phase_benchmark_inline_unique
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,mode=unique-stack-syscall,stack-transport=inline-per-invocation,definitions=${CMAKE_CURRENT_BINARY_DIR}/tier-e-benchmark-inline-unique.mzsd" -cp "${AGENT_TEST_CLASSES}" AgentWorkload 0 platform 100
)
set_tests_properties(agent_phase_benchmark_inline_unique PROPERTIES PASS_REGULAR_EXPRESSION "tier-e-benchmark: stack_transport=inline-per-invocation get_stack_trace_ns=[1-9][0-9]* raw_key_ns=[1-9][0-9]* logical_metadata_ns=[1-9][0-9]* serialization_ns=[1-9][0-9]* publication_bytes=[1-9][0-9]*")

add_test(
    NAME agent_redefinition_smoke_test
    COMMAND "${Java_JAVA_EXECUTABLE}" "-javaagent:${AGENT_TEST_CLASSES}/redefinition-agent.jar" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>" -cp "${AGENT_TEST_CLASSES}" RedefinitionWorkload
)
set_tests_properties(agent_redefinition_smoke_test PROPERTIES PASS_REGULAR_EXPRESSION "redefinition-fixture: status=PASS")

add_test(
    NAME agent_virtual_smoke_test
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>" -cp "${AGENT_TEST_CLASSES}" AgentWorkload 0 virtual 20
)
set_tests_properties(
    agent_virtual_smoke_test
    PROPERTIES PASS_REGULAR_EXPRESSION "proxied-binds=[1-9][0-9]* stack-captures=[1-9][0-9]*"
)

# The standalone workload is also the no-profiler timing control used by the
# privileged Tier E performance measurement.
add_test(
    NAME agent_workload_benchmark_output
    COMMAND "${Java_JAVA_EXECUTABLE}" -cp "${AGENT_TEST_CLASSES}" AgentWorkload
)
set_tests_properties(
    agent_workload_benchmark_output
    PROPERTIES PASS_REGULAR_EXPRESSION "agent-workload: workloadNs=[1-9][0-9]*"
)
