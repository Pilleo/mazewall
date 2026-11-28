cmake_minimum_required(VERSION 3.20)
project(mazewall_stack_agent LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(JNI REQUIRED)
find_package(Java REQUIRED COMPONENTS Development)

option(MAZEWALL_ENABLE_TIER_E_USDT "Emit the experimental Tier E USDT marker note" OFF)
if(MAZEWALL_ENABLE_TIER_E_USDT)
    include(CheckIncludeFileCXX)
    # The optional header may appear after a benchmark image is rebuilt. Do not
    # retain a failed probe from an earlier CMake configure in this build tree.
    unset(MAZEWALL_HAVE_SYS_SDT_H CACHE)
    check_include_file_cxx(sys/sdt.h MAZEWALL_HAVE_SYS_SDT_H)
    if(NOT MAZEWALL_HAVE_SYS_SDT_H)
        message(FATAL_ERROR "MAZEWALL_ENABLE_TIER_E_USDT requires sys/sdt.h (install systemtap-sdt-dev)")
    endif()
endif()

# Keep private inline/template instantiations hidden without changing diagnostic exports.
set_source_files_properties(src/live_stack_context.cpp src/live_stack_jni.cpp src/live_stack_checked_projection.cpp src/private_sha256.cpp src/live_startup_binding.cpp src/live_distribution_manifest.cpp src/live_startup_capture.cpp src/live_java_observation.cpp PROPERTIES
    COMPILE_OPTIONS "-fvisibility=hidden;-fvisibility-inlines-hidden")
add_library(mazewall_stack_agent SHARED src/agent.cpp src/live_stack_context.cpp src/live_stack_jni.cpp src/live_stack_checked_projection.cpp)
target_include_directories(mazewall_stack_agent PRIVATE include ${JNI_INCLUDE_DIRS})
target_compile_options(mazewall_stack_agent PRIVATE -Wall -Wextra -Werror)
if(MAZEWALL_ENABLE_TIER_E_USDT)
    target_compile_definitions(mazewall_stack_agent PRIVATE MAZEWALL_ENABLE_TIER_E_USDT)
endif()
set_target_properties(mazewall_stack_agent PROPERTIES OUTPUT_NAME mazewall_stack_agent)
add_library(mazewall_stack_agent_managed_fixture SHARED EXCLUDE_FROM_ALL
    src/agent.cpp src/live_stack_context.cpp src/live_stack_jni.cpp src/live_stack_checked_projection.cpp)
target_include_directories(mazewall_stack_agent_managed_fixture PRIVATE include ${JNI_INCLUDE_DIRS})
target_compile_definitions(mazewall_stack_agent_managed_fixture PRIVATE MAZEWALL_MANAGED_T4_FIXTURE)
target_compile_options(mazewall_stack_agent_managed_fixture PRIVATE -Wall -Wextra -Werror)
set_target_properties(mazewall_stack_agent_managed_fixture PROPERTIES OUTPUT_NAME mazewall_stack_agent_managed_fixture)
add_library(mazewall_stack_agent_jni_test SHARED src/agent.cpp src/live_stack_context.cpp src/live_stack_jni.cpp src/live_stack_checked_projection.cpp)
target_include_directories(mazewall_stack_agent_jni_test PRIVATE include ${JNI_INCLUDE_DIRS})
target_compile_definitions(mazewall_stack_agent_jni_test PRIVATE MAZEWALL_LIVE_JNI_TEST)
target_compile_options(mazewall_stack_agent_jni_test PRIVATE -Wall -Wextra -Werror)
foreach(probe_fault RANGE 1 2)
    add_library(live_stack_preparation_fault_${probe_fault} SHARED
        src/agent.cpp src/live_stack_context.cpp src/live_stack_jni.cpp src/live_stack_checked_projection.cpp)
    target_include_directories(live_stack_preparation_fault_${probe_fault} PRIVATE include ${JNI_INCLUDE_DIRS})
    target_compile_definitions(live_stack_preparation_fault_${probe_fault} PRIVATE
        MAZEWALL_LIVE_JNI_TEST MAZEWALL_LIVE_PREPARATION_FAULT=${probe_fault})
    target_compile_options(live_stack_preparation_fault_${probe_fault} PRIVATE -Wall -Wextra -Werror)
endforeach()

enable_testing()
add_executable(live_protected_image_close_test tests/live_protected_image_close_test.cpp)
target_include_directories(live_protected_image_close_test PRIVATE src include ${JNI_INCLUDE_DIRS})
target_compile_options(live_protected_image_close_test PRIVATE -Wall -Wextra -Werror)
target_link_options(live_protected_image_close_test PRIVATE -Wl,--wrap=close -Wl,--wrap=fstat)
add_test(NAME live_protected_image_close_test COMMAND live_protected_image_close_test)
set_tests_properties(live_protected_image_close_test PROPERTIES TIMEOUT 60)
add_executable(live_java_observation_test tests/live_java_observation_test.cpp src/live_java_observation.cpp src/live_distribution_manifest.cpp)
target_include_directories(live_java_observation_test PRIVATE src ${JNI_INCLUDE_DIRS})
target_compile_options(live_java_observation_test PRIVATE -Wall -Wextra -Werror)
target_link_libraries(live_java_observation_test PRIVATE ${JNI_LIBRARIES})
add_test(NAME live_java_observation_test COMMAND live_java_observation_test "${CMAKE_CURRENT_SOURCE_DIR}/tests/live_startup_manifest_valid.txt")
set_tests_properties(live_java_observation_test PROPERTIES TIMEOUT 60)
add_executable(live_startup_capture_fault_test tests/live_startup_capture_fault_test.cpp src/live_startup_binding.cpp src/live_distribution_manifest.cpp src/live_startup_capture.cpp src/private_sha256.cpp)
target_include_directories(live_startup_capture_fault_test PRIVATE src)
target_compile_options(live_startup_capture_fault_test PRIVATE -Wall -Wextra -Werror)
target_link_options(live_startup_capture_fault_test PRIVATE -Wl,--wrap=open -Wl,--wrap=openat -Wl,--wrap=close -Wl,--wrap=fstat -Wl,--wrap=read)
add_test(NAME live_startup_capture_fault_test COMMAND live_startup_capture_fault_test "${CMAKE_CURRENT_SOURCE_DIR}/tests/live_startup_manifest_valid.txt" /tmp)
set_tests_properties(live_startup_capture_fault_test PROPERTIES TIMEOUT 60)
add_executable(live_startup_binding_test tests/live_startup_binding_test.cpp src/live_startup_binding.cpp src/live_distribution_manifest.cpp src/live_startup_capture.cpp src/private_sha256.cpp)
target_include_directories(live_startup_binding_test PRIVATE src)
target_compile_options(live_startup_binding_test PRIVATE -Wall -Wextra -Werror)
add_test(NAME live_startup_binding_test COMMAND live_startup_binding_test "${CMAKE_CURRENT_SOURCE_DIR}/tests/live_startup_manifest_valid.txt")
set_tests_properties(live_startup_binding_test PROPERTIES TIMEOUT 60)
add_executable(private_sha256_test tests/private_sha256_test.cpp src/private_sha256.cpp)
target_include_directories(private_sha256_test PRIVATE src)
target_compile_options(private_sha256_test PRIVATE -Wall -Wextra -Werror)
add_test(NAME private_sha256_test COMMAND private_sha256_test "${CMAKE_CURRENT_SOURCE_DIR}/tests/private_sha256_vectors.txt")
set_tests_properties(private_sha256_test PROPERTIES TIMEOUT 60)

add_executable(live_stack_agent_test tests/live_stack_agent_test.cpp src/live_stack_context.cpp src/live_stack_checked_projection.cpp)
target_compile_definitions(live_stack_agent_test PRIVATE MAZEWALL_LIVE_TEST)
add_library(live_stack_participant MODULE tests/live_stack_agent_test.cpp)
target_include_directories(live_stack_participant PRIVATE ${JNI_INCLUDE_DIRS})
target_compile_definitions(live_stack_participant PRIVATE MAZEWALL_LIVE_PARTICIPANT_TEST)
target_compile_options(live_stack_participant PRIVATE -Wall -Wextra -Werror)
add_custom_command(TARGET live_stack_participant POST_BUILD
    COMMAND "${CMAKE_COMMAND}" -E copy $<TARGET_FILE:live_stack_participant> "${CMAKE_CURRENT_BINARY_DIR}/renamed-participant.so")
target_include_directories(live_stack_agent_test PRIVATE include ${JNI_INCLUDE_DIRS})
target_compile_options(live_stack_agent_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_executable(live_stack_preparation_test tests/live_stack_agent_test.cpp src/live_stack_context.cpp src/live_stack_checked_projection.cpp)
target_include_directories(live_stack_preparation_test PRIVATE include ${JNI_INCLUDE_DIRS})
target_compile_definitions(live_stack_preparation_test PRIVATE MAZEWALL_LIVE_JNI_TEST MAZEWALL_LIVE_PREPARATION_TEST)
target_compile_options(live_stack_preparation_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
target_link_libraries(live_stack_preparation_test PRIVATE ${JNI_LIBRARIES})
foreach(probe_case false true throw pending prepared active dispatch-owner)
    add_test(NAME live_stack_agent_probe_preparation_${probe_case}
        COMMAND live_stack_preparation_test ${probe_case} "${CMAKE_CURRENT_BINARY_DIR}/live-stack-fixture.jar")
    set_tests_properties(live_stack_agent_probe_preparation_${probe_case} PROPERTIES TIMEOUT 90)
endforeach()
add_test(NAME live_stack_agent_options COMMAND live_stack_agent_test options)
add_test(NAME live_stack_agent_startup COMMAND live_stack_agent_test startup)
add_test(NAME live_stack_agent_guard COMMAND live_stack_agent_test guard)
add_test(NAME live_stack_agent_guard_component COMMAND live_stack_agent_test guard-component)
add_test(NAME live_stack_agent_guard_component_openat0 COMMAND live_stack_agent_test guard-component-openat0)
add_test(NAME live_stack_agent_capture COMMAND live_stack_agent_test capture)
add_test(NAME live_stack_agent_capture_component COMMAND live_stack_agent_test capture-component)
add_test(NAME live_stack_agent_capture_component_openat0 COMMAND live_stack_agent_test capture-component-openat0)
add_executable(open_boundary_recorder_test tests/open_boundary_recorder_test.cpp src/live_stack_context.cpp src/live_stack_jni.cpp src/live_stack_checked_projection.cpp)
target_include_directories(open_boundary_recorder_test PRIVATE include ${JNI_INCLUDE_DIRS})
target_compile_options(open_boundary_recorder_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
target_link_options(open_boundary_recorder_test PRIVATE -Wl,--wrap=__cxa_thread_atexit
    -Wl,--wrap=_Znwm -Wl,--wrap=_ZnwmRKSt9nothrow_t)
add_test(NAME open_boundary_recorder_test COMMAND open_boundary_recorder_test)
add_test(NAME open_boundary_export_test COMMAND open_boundary_recorder_test --exports $<TARGET_FILE:mazewall_stack_agent>)

add_executable(live_stack_context_test tests/live_stack_context_test.cpp src/live_stack_context.cpp)
target_include_directories(live_stack_context_test PRIVATE include)
target_compile_definitions(live_stack_context_test PRIVATE MAZEWALL_LIVE_TEST)
target_compile_options(live_stack_context_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
target_link_libraries(live_stack_context_test PRIVATE ${CMAKE_DL_LIBS})
add_test(NAME live_stack_context_test COMMAND live_stack_context_test)
foreach(live_case shutdown ids serial runtime binding helper hooks
        publish-shutdown publish-generation read-shutdown read-generation
        probe-shutdown probe-ids probe-serial probe-replacement)
    add_test(NAME live_stack_context_${live_case}_test COMMAND live_stack_context_test ${live_case})
    set_tests_properties(live_stack_context_${live_case}_test PROPERTIES TIMEOUT 60)
endforeach()
add_test(NAME live_stack_context_export_test COMMAND live_stack_context_test --exports $<TARGET_FILE:mazewall_stack_agent>)
set_tests_properties(live_stack_context_test live_stack_context_export_test PROPERTIES TIMEOUT 60)

add_executable(invocation_registry_test tests/invocation_registry_test.cpp)
target_include_directories(invocation_registry_test PRIVATE include)
target_compile_options(invocation_registry_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_test(NAME invocation_registry_test COMMAND invocation_registry_test)

add_executable(binding_manifest_test tests/binding_manifest_test.cpp)
target_include_directories(binding_manifest_test PRIVATE include)
target_compile_options(binding_manifest_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_test(NAME binding_manifest_test COMMAND binding_manifest_test)

add_executable(capture_profile_test tests/capture_profile_test.cpp)
target_include_directories(capture_profile_test PRIVATE include)
target_compile_options(capture_profile_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_test(NAME capture_profile_test COMMAND capture_profile_test)

add_executable(capture_yield_recorder_test tests/capture_yield_recorder_test.cpp)
target_include_directories(capture_yield_recorder_test PRIVATE include)
target_compile_options(capture_yield_recorder_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_test(NAME capture_yield_recorder_test COMMAND capture_yield_recorder_test)

add_executable(capture_yield_agent_test tests/capture_yield_agent_test.cpp src/live_stack_context.cpp src/live_stack_jni.cpp src/live_stack_checked_projection.cpp)
target_include_directories(capture_yield_agent_test PRIVATE include ${JNI_INCLUDE_DIRS})
target_compile_definitions(capture_yield_agent_test PRIVATE MAZEWALL_CAPTURE_YIELD_TEST)
target_compile_options(capture_yield_agent_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
target_link_options(capture_yield_agent_test PRIVATE -Wl,--wrap=_Znwm)
add_executable(definition_watermark_test tests/definition_watermark_test.cpp src/live_stack_context.cpp
    src/live_stack_jni.cpp src/live_stack_checked_projection.cpp)
target_include_directories(definition_watermark_test PRIVATE include ${JNI_INCLUDE_DIRS})
target_compile_options(definition_watermark_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_test(NAME definition_watermark_test
    COMMAND definition_watermark_test "${CMAKE_CURRENT_BINARY_DIR}/definition-watermark.mzse")
set_tests_properties(definition_watermark_test PROPERTIES TIMEOUT 30)
foreach(verifier_target mazewall_stack_agent mazewall_stack_agent_managed_fixture mazewall_stack_agent_jni_test
        live_stack_preparation_fault_1 live_stack_preparation_fault_2 live_stack_agent_test live_stack_preparation_test
        open_boundary_recorder_test capture_yield_agent_test definition_watermark_test)
    target_sources(${verifier_target} PRIVATE src/private_sha256.cpp src/live_startup_binding.cpp
        src/live_distribution_manifest.cpp src/live_startup_capture.cpp src/live_java_observation.cpp)
endforeach()
foreach(yield_case overlap close-error clean unload hole stale-close stale-full overflow errno last-slot
        disabled zero open-error flush-error rename-error allocation-error)
    add_test(NAME capture_yield_agent_${yield_case} COMMAND capture_yield_agent_test ${yield_case})
    set_tests_properties(capture_yield_agent_${yield_case} PROPERTIES TIMEOUT 30)
endforeach()
set_tests_properties(capture_yield_agent_unload PROPERTIES
    PASS_REGULAR_EXPRESSION "capture-yield=failed" FAIL_REGULAR_EXPRESSION "capture-yield=written")
add_test(NAME capture_yield_agent_full COMMAND capture_yield_agent_test full $<TARGET_FILE:capture_yield_file_test>)
set_tests_properties(capture_yield_agent_full PROPERTIES TIMEOUT 30)

add_executable(invocation_fd_recorder_test tests/invocation_fd_recorder_test.cpp)
target_include_directories(invocation_fd_recorder_test PRIVATE include)
target_compile_options(invocation_fd_recorder_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_test(NAME invocation_fd_recorder_test COMMAND invocation_fd_recorder_test)

add_executable(capture_yield_file_test tests/capture_yield_file_test.cpp)
target_include_directories(capture_yield_file_test PRIVATE include)
target_compile_options(capture_yield_file_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)

add_executable(session_bound_definition_file_test tests/session_bound_definition_file_test.cpp)
target_compile_options(session_bound_definition_file_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)

add_executable(marker_export_test tests/marker_export_test.cpp)
target_compile_options(marker_export_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
target_link_libraries(marker_export_test PRIVATE ${CMAKE_DL_LIBS})
add_test(NAME marker_export_test COMMAND marker_export_test $<TARGET_FILE:mazewall_stack_agent>)

add_executable(marker_version_proof_test tests/marker_version_proof_test.cpp)
target_include_directories(marker_version_proof_test PRIVATE include)
target_compile_options(marker_version_proof_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_test(NAME marker_version_proof_test COMMAND marker_version_proof_test)

add_executable(usdt_marker_note_test tests/usdt_marker_note_test.cpp)
target_compile_options(usdt_marker_note_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
if(MAZEWALL_ENABLE_TIER_E_USDT)
    set(USDT_MARKER_EXPECTED enabled)
else()
    set(USDT_MARKER_EXPECTED disabled)
endif()
add_test(NAME usdt_marker_note_test COMMAND usdt_marker_note_test $<TARGET_FILE:mazewall_stack_agent> ${USDT_MARKER_EXPECTED})

add_executable(seccomp_boundary_fixture tests/seccomp_boundary_fixture.cpp)
target_compile_options(seccomp_boundary_fixture PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_test(NAME seccomp_boundary_fixture COMMAND seccomp_boundary_fixture)

add_executable(symbolization_generation_test tests/symbolization_generation_test.cpp)
target_include_directories(symbolization_generation_test PRIVATE include)
target_compile_options(symbolization_generation_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_test(NAME symbolization_generation_test COMMAND symbolization_generation_test)

add_executable(capture_timing_diagnostic_test tests/capture_timing_diagnostic_test.cpp)
target_include_directories(capture_timing_diagnostic_test PRIVATE include)
target_compile_options(capture_timing_diagnostic_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_test(NAME capture_timing_diagnostic_test COMMAND capture_timing_diagnostic_test)

add_executable(lifecycle_identity_fixture tests/lifecycle_identity_fixture.cpp)
target_compile_options(lifecycle_identity_fixture PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_test(NAME lifecycle_identity_fixture COMMAND lifecycle_identity_fixture)

add_executable(lifecycle_identity_stress_fixture tests/lifecycle_identity_stress_fixture.cpp)
target_compile_options(lifecycle_identity_stress_fixture PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_test(NAME lifecycle_identity_stress_fixture COMMAND lifecycle_identity_stress_fixture)

add_executable(lifecycle_barrier_test tests/lifecycle_barrier_test.cpp)
target_include_directories(lifecycle_barrier_test PRIVATE include)
target_compile_options(lifecycle_barrier_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_test(NAME lifecycle_barrier_test COMMAND lifecycle_barrier_test)

set(AGENT_TEST_CLASSES "${CMAKE_CURRENT_BINARY_DIR}/agent-test-classes")
set(LIVE_TEST_CLASS "${AGENT_TEST_CLASSES}/io/mazewall/ffi/internal/LiveStackContextWorkload.class")
set(LIVE_HELPER_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/../platform/src/main/java/io/mazewall/ffi/internal/LiveStackBootstrap.java")
set(LIVE_HELPER_CLASS "${AGENT_TEST_CLASSES}/io/mazewall/ffi/internal/LiveStackBootstrap.class")
set(LIVE_OBSERVER_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/../platform/src/main/java/io/mazewall/ffi/internal/ProductionDeploymentVerifier.java")
set(LIVE_OBSERVER_CLASS "${AGENT_TEST_CLASSES}/io/mazewall/ffi/internal/ProductionDeploymentVerifier.class")
add_custom_command(OUTPUT "${LIVE_TEST_CLASS}" "${LIVE_HELPER_CLASS}" "${LIVE_OBSERVER_CLASS}"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${AGENT_TEST_CLASSES}"
    COMMAND "${Java_JAVAC_EXECUTABLE}" --release 22 -d "${AGENT_TEST_CLASSES}"
        "${CMAKE_CURRENT_SOURCE_DIR}/tests/LiveStackContextWorkload.java"
        "${LIVE_HELPER_SOURCE}" "${LIVE_OBSERVER_SOURCE}"
    DEPENDS tests/LiveStackContextWorkload.java "${LIVE_HELPER_SOURCE}" "${LIVE_OBSERVER_SOURCE}" VERBATIM)
foreach(startup_case only shared missing loader mismatch annotated)
    add_test(NAME live_stack_agent_${startup_case}
        COMMAND "${Java_JAVA_EXECUTABLE}" -cp "${AGENT_TEST_CLASSES}"
            io.mazewall.ffi.internal.LiveStackContextWorkload ${startup_case}
            $<TARGET_FILE:mazewall_stack_agent> "${AGENT_TEST_CLASSES}"
            "${CMAKE_CURRENT_BINARY_DIR}/live-startup-${startup_case}")
    set_tests_properties(live_stack_agent_${startup_case} PROPERTIES TIMEOUT 45)
endforeach()
set(LIVE_FIXTURE_JAR "${CMAKE_CURRENT_BINARY_DIR}/live-stack-fixture.jar")
foreach(probe_startup probe_true probe_true_shared probe_throw probe_throw_shared)
    set(probe_fault 1)
    if(probe_startup MATCHES "^probe_throw")
        set(probe_fault 2)
    endif()
    add_test(NAME live_stack_agent_${probe_startup}
        COMMAND "${Java_JAVA_EXECUTABLE}" -cp "${AGENT_TEST_CLASSES}"
            io.mazewall.ffi.internal.LiveStackContextWorkload ${probe_startup}
            $<TARGET_FILE:live_stack_preparation_fault_${probe_fault}> "${AGENT_TEST_CLASSES}"
            "${CMAKE_CURRENT_BINARY_DIR}/live-startup-${probe_startup}")
    set_tests_properties(live_stack_agent_${probe_startup} PROPERTIES TIMEOUT 45)
endforeach()
add_custom_command(OUTPUT "${LIVE_FIXTURE_JAR}"
    COMMAND "${Java_JAR_EXECUTABLE}" --create --file "${LIVE_FIXTURE_JAR}"
        -C "${AGENT_TEST_CLASSES}" io/mazewall/ffi/internal
    DEPENDS "${LIVE_TEST_CLASS}" "${LIVE_HELPER_CLASS}" "${LIVE_OBSERVER_CLASS}" VERBATIM)
add_custom_target(live_stack_fixture_jar ALL DEPENDS "${LIVE_FIXTURE_JAR}")
add_dependencies(live_stack_fixture_jar live_stack_participant)
set(LIVE_PREPARATION_CASES deployment enrollment enrollment_minimum deployment_shared enrollment_shared deployment_negative deployment_absent_binding)
if(NOT Java_VERSION_STRING STREQUAL "25.0.5")
    list(APPEND LIVE_PREPARATION_CASES host_runtime_refusal)
endif()
foreach(preparation_case IN LISTS LIVE_PREPARATION_CASES)
    set(preparation_variant only)
    if(preparation_case MATCHES "_shared$")
        set(preparation_variant shared)
    endif()
    add_test(NAME live_stack_agent_${preparation_case}
        COMMAND "${Java_JAVA_EXECUTABLE}" -XX:+DisableAttachMechanism
            -XX:-EnableDynamicAgentLoading -Xshare:off -XX:+UnlockExperimentalVMOptions -XX:-EnableJVMCI
            --enable-native-access=ALL-UNNAMED --add-opens=java.base/sun.nio.fs=ALL-UNNAMED
            "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=live-stack=${preparation_variant}"
            -cp "${LIVE_FIXTURE_JAR}" io.mazewall.ffi.internal.LiveStackContextWorkload
            ${preparation_case} $<TARGET_FILE:mazewall_stack_agent>
            "${CMAKE_CURRENT_SOURCE_DIR}/.." "${CMAKE_CURRENT_BINARY_DIR}/live-preparation")
    set_tests_properties(live_stack_agent_${preparation_case} PROPERTIES TIMEOUT 90)
endforeach()
add_test(NAME live_stack_agent_deployment_init_failure
    COMMAND "${Java_JAVA_EXECUTABLE}" -XX:+DisableAttachMechanism -XX:-EnableDynamicAgentLoading
        -Xshare:off -XX:+UnlockExperimentalVMOptions -XX:-EnableJVMCI
        --enable-native-access=ALL-UNNAMED --add-opens=java.base/sun.nio.fs=ALL-UNNAMED
        "-agentpath:$<TARGET_FILE:mazewall_stack_agent_jni_test>=live-stack=only"
        -cp "${LIVE_FIXTURE_JAR}" io.mazewall.ffi.internal.LiveStackContextWorkload
        deployment_init_failure $<TARGET_FILE:mazewall_stack_agent_jni_test>
        "${CMAKE_CURRENT_SOURCE_DIR}/.." "${CMAKE_CURRENT_BINARY_DIR}/live-preparation")
set_tests_properties(live_stack_agent_deployment_init_failure PROPERTIES TIMEOUT 90)
foreach(jni_case deployment_projection_dispatch deployment_rebind_check deployment_rebind_commit enrollment_validation
        enrollment_rollback enrollment_retention enrollment_replacement enrollment_seal_race
        deployment_resolved_original deployment_resolved_dependency enrollment_helper_mutation
        enrollment_revocation enrollment_postseal_replacement deployment_rollback enrollment_null_reference
        guard_runtime guard_enable_failure)
    add_test(NAME live_stack_agent_${jni_case}
        COMMAND "${Java_JAVA_EXECUTABLE}" -XX:+DisableAttachMechanism -XX:-EnableDynamicAgentLoading
            -Xshare:off -XX:+UnlockExperimentalVMOptions -XX:-EnableJVMCI
            --enable-native-access=ALL-UNNAMED --add-opens=java.base/sun.nio.fs=ALL-UNNAMED
            "-agentpath:$<TARGET_FILE:mazewall_stack_agent_jni_test>=live-stack=only"
            -cp "${LIVE_FIXTURE_JAR}" io.mazewall.ffi.internal.LiveStackContextWorkload
            ${jni_case} $<TARGET_FILE:mazewall_stack_agent_jni_test>
            "${CMAKE_CURRENT_SOURCE_DIR}/.." "${CMAKE_CURRENT_BINARY_DIR}/live-preparation")
    set_tests_properties(live_stack_agent_${jni_case} PROPERTIES TIMEOUT 90)
endforeach()
foreach(fifo_variant only shared)
    add_test(NAME live_stack_agent_guard_fifo_${fifo_variant}
        COMMAND "${Java_JAVA_EXECUTABLE}" -XX:+DisableAttachMechanism -XX:-EnableDynamicAgentLoading
            -Xshare:off -XX:+UnlockExperimentalVMOptions -XX:-EnableJVMCI
            --enable-native-access=ALL-UNNAMED --add-opens=java.base/sun.nio.fs=ALL-UNNAMED
            "-agentpath:$<TARGET_FILE:mazewall_stack_agent_jni_test>=live-stack=${fifo_variant}"
            -cp "${LIVE_FIXTURE_JAR}" io.mazewall.ffi.internal.LiveStackContextWorkload
            guard_fifo_${fifo_variant} $<TARGET_FILE:mazewall_stack_agent_jni_test>
            "${CMAKE_CURRENT_SOURCE_DIR}/.." "${CMAKE_CURRENT_BINARY_DIR}/live-preparation")
    set_tests_properties(live_stack_agent_guard_fifo_${fifo_variant} PROPERTIES TIMEOUT 90)
    add_test(NAME live_stack_agent_guard_fifo_openat_${fifo_variant}
        COMMAND "${Java_JAVA_EXECUTABLE}" -XX:+DisableAttachMechanism -XX:-EnableDynamicAgentLoading
            -Xshare:off -XX:+UnlockExperimentalVMOptions -XX:-EnableJVMCI
            --enable-native-access=ALL-UNNAMED --add-opens=java.base/sun.nio.fs=ALL-UNNAMED
            "-agentpath:$<TARGET_FILE:mazewall_stack_agent_jni_test>=live-stack=${fifo_variant}"
            -cp "${LIVE_FIXTURE_JAR}" io.mazewall.ffi.internal.LiveStackContextWorkload
            guard_fifo_openat_${fifo_variant} $<TARGET_FILE:mazewall_stack_agent_jni_test>
            "${CMAKE_CURRENT_SOURCE_DIR}/.." "${CMAKE_CURRENT_BINARY_DIR}/live-preparation")
    set_tests_properties(live_stack_agent_guard_fifo_openat_${fifo_variant} PROPERTIES TIMEOUT 90)
endforeach()
add_test(NAME live_stack_agent_guard_runtime_shared
    COMMAND "${Java_JAVA_EXECUTABLE}" -XX:+DisableAttachMechanism -XX:-EnableDynamicAgentLoading
        -Xshare:off -XX:+UnlockExperimentalVMOptions -XX:-EnableJVMCI
        --enable-native-access=ALL-UNNAMED --add-opens=java.base/sun.nio.fs=ALL-UNNAMED
        "-agentpath:$<TARGET_FILE:mazewall_stack_agent_jni_test>=live-stack=shared"
        -cp "${LIVE_FIXTURE_JAR}" io.mazewall.ffi.internal.LiveStackContextWorkload
        guard_runtime_shared $<TARGET_FILE:mazewall_stack_agent_jni_test>
        "${CMAKE_CURRENT_SOURCE_DIR}/.." "${CMAKE_CURRENT_BINARY_DIR}/live-preparation")
set_tests_properties(live_stack_agent_guard_runtime_shared PROPERTIES TIMEOUT 90)
add_test(NAME live_stack_agent_deployment_launch_matrix
    COMMAND "${Java_JAVA_EXECUTABLE}" -cp "${LIVE_FIXTURE_JAR}" io.mazewall.ffi.internal.LiveStackContextWorkload
        deployment_launch_matrix $<TARGET_FILE:mazewall_stack_agent>
        "${CMAKE_CURRENT_SOURCE_DIR}/.." "${CMAKE_CURRENT_BINARY_DIR}/live-preparation")
set_tests_properties(live_stack_agent_deployment_launch_matrix PROPERTIES TIMEOUT 90)
add_custom_command(
    OUTPUT "${AGENT_TEST_CLASSES}/AgentWorkload.class" "${AGENT_TEST_CLASSES}/OpenatProfileWorkload.class" "${AGENT_TEST_CLASSES}/AgentCoverageWorkload.class" "${AGENT_TEST_CLASSES}/NativeOutcomeWorkload.class" "${AGENT_TEST_CLASSES}/InvocationFdWorkload.class" "${AGENT_TEST_CLASSES}/TierEAllTargetWorkload.class" "${AGENT_TEST_CLASSES}/OpenBoundaryWorkload.class"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${AGENT_TEST_CLASSES}"
    COMMAND "${Java_JAVAC_EXECUTABLE}" -d "${AGENT_TEST_CLASSES}" "${CMAKE_CURRENT_SOURCE_DIR}/tests/AgentWorkload.java" "${CMAKE_CURRENT_SOURCE_DIR}/tests/OpenatProfileWorkload.java" "${CMAKE_CURRENT_SOURCE_DIR}/tests/AgentCoverageWorkload.java" "${CMAKE_CURRENT_SOURCE_DIR}/tests/NativeOutcomeWorkload.java" "${CMAKE_CURRENT_SOURCE_DIR}/tests/InvocationFdWorkload.java" "${CMAKE_CURRENT_SOURCE_DIR}/tests/TierEAllTargetWorkload.java" "${CMAKE_CURRENT_SOURCE_DIR}/tests/OpenBoundaryWorkload.java"
    DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/tests/AgentWorkload.java" "${CMAKE_CURRENT_SOURCE_DIR}/tests/OpenatProfileWorkload.java" "${CMAKE_CURRENT_SOURCE_DIR}/tests/AgentCoverageWorkload.java" "${CMAKE_CURRENT_SOURCE_DIR}/tests/NativeOutcomeWorkload.java" "${CMAKE_CURRENT_SOURCE_DIR}/tests/InvocationFdWorkload.java" "${CMAKE_CURRENT_SOURCE_DIR}/tests/TierEAllTargetWorkload.java" "${CMAKE_CURRENT_SOURCE_DIR}/tests/OpenBoundaryWorkload.java"
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
add_custom_target(agent_workload_test_classes ALL DEPENDS "${AGENT_TEST_CLASSES}/AgentWorkload.class" "${AGENT_TEST_CLASSES}/OpenatProfileWorkload.class" "${AGENT_TEST_CLASSES}/AgentCoverageWorkload.class" "${AGENT_TEST_CLASSES}/NativeOutcomeWorkload.class" "${AGENT_TEST_CLASSES}/InvocationFdWorkload.class" "${AGENT_TEST_CLASSES}/TierEAllTargetWorkload.class" "${AGENT_TEST_CLASSES}/redefinition-agent.jar" "${AGENT_TEST_CLASSES}/OpenBoundaryWorkload.class" "${LIVE_TEST_CLASS}" "${LIVE_HELPER_CLASS}")
foreach(boundary_mode enabled disabled)
    add_test(NAME agent_open_boundary_${boundary_mode}
        COMMAND "${Java_JAVA_EXECUTABLE}" -cp "${AGENT_TEST_CLASSES}" OpenBoundaryWorkload
            ${boundary_mode} $<TARGET_FILE:mazewall_stack_agent>)
endforeach()
add_test(
    NAME agent_native_outcome_preservation
    COMMAND "${Java_JAVA_EXECUTABLE}" "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED"
        "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,capture-profile=jdk25-unix-openat0,force-capture-failure-once"
        -cp "${AGENT_TEST_CLASSES}" NativeOutcomeWorkload
)
set_tests_properties(agent_native_outcome_preservation PROPERTIES
    PASS_REGULAR_EXPRESSION "native-outcome-workload: return=preserved exception=preserved errno=2")
add_test(
    NAME agent_checked_jni_native_outcome
    COMMAND "${Java_JAVA_EXECUTABLE}" -Xcheck:jni "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED"
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED" --enable-native-access=ALL-UNNAMED
        "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases"
        -cp "${AGENT_TEST_CLASSES}" NativeOutcomeWorkload checked
)
set_tests_properties(agent_checked_jni_native_outcome PROPERTIES
    PASS_REGULAR_EXPRESSION "checked-native-outcomes: preserved"
    FAIL_REGULAR_EXPRESSION "JNI call made with exception pending"
    TIMEOUT 30)
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
set_tests_properties(agent_phase_benchmark_full PROPERTIES PASS_REGULAR_EXPRESSION "tier-e-benchmark: stack_transport=dictionary get_stack_trace_ns=[1-9][0-9]* raw_key_ns=[1-9][0-9]* raw_hash_ns=[1-9][0-9]* raw_equality_ns=[1-9][0-9]* generation_validation_ns=[1-9][0-9]* guard_validation_ns=[1-9][0-9]* canonical_cache_lock_wait_ns=[1-9][0-9]* logical_metadata_ns=[1-9][0-9]* .*serialization_ns=[1-9][0-9]* publication_bytes=[1-9][0-9]*")

add_test(
    NAME agent_phase_comparable_full
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,mode=full-stream,definitions=${CMAKE_CURRENT_BINARY_DIR}/tier-e-comparable-full.mzsd" -cp "${AGENT_TEST_CLASSES}" AgentWorkload 0 platform 100
)
set_tests_properties(agent_phase_comparable_full PROPERTIES PASS_REGULAR_EXPRESSION "canonical_cache_lookup_ns=[1-9][0-9]*")
set_tests_properties(agent_phase_comparable_full PROPERTIES FAIL_REGULAR_EXPRESSION "capture-timing-diagnostic:")

add_test(
    NAME agent_capture_timing_diagnostic_full
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,capture-timing-diagnostic,mode=full-stream" -cp "${AGENT_TEST_CLASSES}" AgentWorkload 0 platform 100
)
set_tests_properties(agent_capture_timing_diagnostic_full PROPERTIES PASS_REGULAR_EXPRESSION "capture-timing-diagnostic: attempted=514 completed=514 invalid=0 .*clock_pair_ns=[1-9][0-9]*")

add_test(
    NAME agent_capture_profile_jdk25_openat
    COMMAND "${Java_JAVA_EXECUTABLE}" "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,capture-profile=jdk25-unix-openat0" -cp "${AGENT_TEST_CLASSES}" OpenatProfileWorkload
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
    NAME agent_invocation_fd_sidecar
    COMMAND "${Java_JAVA_EXECUTABLE}" "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED"
        "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,session=4d5a,capture-profile=jdk25-unix-openat0,invocation-fd=${CMAKE_CURRENT_BINARY_DIR}/tier-e-invocation-fd.mzif"
        -cp "${AGENT_TEST_CLASSES}" InvocationFdWorkload
)
set_tests_properties(agent_invocation_fd_sidecar PROPERTIES PASS_REGULAR_EXPRESSION "invocation-fd=written")

add_executable(invocation_fd_file_test tests/invocation_fd_file_test.cpp)
target_include_directories(invocation_fd_file_test PRIVATE include)
target_compile_options(invocation_fd_file_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_test(NAME invocation_fd_file_test COMMAND invocation_fd_file_test "${CMAKE_CURRENT_BINARY_DIR}/tier-e-invocation-fd.mzif")
set_tests_properties(invocation_fd_file_test PROPERTIES DEPENDS agent_invocation_fd_sidecar)

add_test(
    NAME agent_phase_benchmark_unique
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,mode=unique-stack-syscall,definitions=${CMAKE_CURRENT_BINARY_DIR}/tier-e-benchmark-dictionary-unique.mzsd" -cp "${AGENT_TEST_CLASSES}" AgentWorkload 0 platform 100
)
set_tests_properties(agent_phase_benchmark_unique PROPERTIES PASS_REGULAR_EXPRESSION "tier-e-benchmark: stack_transport=dictionary get_stack_trace_ns=[1-9][0-9]* raw_key_ns=[1-9][0-9]* raw_hash_ns=[1-9][0-9]* raw_equality_ns=[1-9][0-9]* generation_validation_ns=[1-9][0-9]* guard_validation_ns=[1-9][0-9]* canonical_cache_lock_wait_ns=[1-9][0-9]* logical_metadata_ns=[1-9][0-9]* .*serialization_ns=[1-9][0-9]* publication_bytes=[1-9][0-9]*")

add_test(
    NAME agent_phase_comparable_unique
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,mode=unique-stack-syscall,definitions=${CMAKE_CURRENT_BINARY_DIR}/tier-e-comparable-unique.mzsd" -cp "${AGENT_TEST_CLASSES}" AgentWorkload 0 platform 100
)
set_tests_properties(agent_phase_comparable_unique PROPERTIES PASS_REGULAR_EXPRESSION "canonical_cache_lookup_ns=[1-9][0-9]*")
set_tests_properties(agent_phase_comparable_unique PROPERTIES FAIL_REGULAR_EXPRESSION "capture-timing-diagnostic:")

add_test(
    NAME agent_capture_timing_diagnostic_unique
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,capture-timing-diagnostic,mode=unique-stack-syscall" -cp "${AGENT_TEST_CLASSES}" AgentWorkload 0 platform 100
)
set_tests_properties(agent_capture_timing_diagnostic_unique PROPERTIES PASS_REGULAR_EXPRESSION "capture-timing-diagnostic: attempted=514 completed=514 invalid=0 .*clock_pair_ns=[1-9][0-9]*")

add_test(
    NAME agent_phase_benchmark_full_disabled
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,mode=full-stream" -cp "${AGENT_TEST_CLASSES}" AgentWorkload 0 platform 100
)
set_tests_properties(agent_phase_benchmark_full_disabled PROPERTIES PASS_REGULAR_EXPRESSION "tier-e-benchmark: stack_transport=dictionary get_stack_trace_ns=[1-9][0-9]* raw_key_ns=[1-9][0-9]* raw_hash_ns=[1-9][0-9]* raw_equality_ns=[1-9][0-9]* generation_validation_ns=[1-9][0-9]* guard_validation_ns=[1-9][0-9]* canonical_cache_lock_wait_ns=[1-9][0-9]* logical_metadata_ns=[1-9][0-9]*")

add_test(
    NAME agent_phase_benchmark_unique_disabled
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,mode=unique-stack-syscall" -cp "${AGENT_TEST_CLASSES}" AgentWorkload 0 platform 100
)
set_tests_properties(agent_phase_benchmark_unique_disabled PROPERTIES PASS_REGULAR_EXPRESSION "tier-e-benchmark: stack_transport=dictionary get_stack_trace_ns=[1-9][0-9]* raw_key_ns=[1-9][0-9]* raw_hash_ns=[1-9][0-9]* raw_equality_ns=[1-9][0-9]* generation_validation_ns=[1-9][0-9]* guard_validation_ns=[1-9][0-9]* canonical_cache_lock_wait_ns=[1-9][0-9]* logical_metadata_ns=[1-9][0-9]*")

add_test(
    NAME agent_phase_benchmark_full_live
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,mode=full-stream,live-definitions,definitions=${CMAKE_CURRENT_BINARY_DIR}/tier-e-benchmark-dictionary-full-live.mzsd" -cp "${AGENT_TEST_CLASSES}" AgentWorkload 0 platform 100
)
set_tests_properties(agent_phase_benchmark_full_live PROPERTIES PASS_REGULAR_EXPRESSION "tier-e-benchmark: stack_transport=dictionary get_stack_trace_ns=[1-9][0-9]* raw_key_ns=[1-9][0-9]* raw_hash_ns=[1-9][0-9]* raw_equality_ns=[1-9][0-9]* generation_validation_ns=[1-9][0-9]* guard_validation_ns=[1-9][0-9]* canonical_cache_lock_wait_ns=[1-9][0-9]* logical_metadata_ns=[1-9][0-9]* .*serialization_ns=[1-9][0-9]* publication_bytes=[1-9][0-9]*")

add_test(
    NAME agent_session_bound_definitions
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,session=2a,session-bound-definitions,definitions=${CMAKE_CURRENT_BINARY_DIR}/tier-e-session-bound.mzse" -cp "${AGENT_TEST_CLASSES}" AgentWorkload 0 platform 10
)
set_tests_properties(agent_session_bound_definitions PROPERTIES PASS_REGULAR_EXPRESSION "tier-e-benchmark: stack_transport=dictionary")
add_test(
    NAME session_bound_definition_file_test
    COMMAND session_bound_definition_file_test "${CMAKE_CURRENT_BINARY_DIR}/tier-e-session-bound.mzse" 2a
)
set_tests_properties(session_bound_definition_file_test PROPERTIES DEPENDS agent_session_bound_definitions)

add_test(
    NAME agent_phase_benchmark_unique_live
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,mode=unique-stack-syscall,live-definitions,definitions=${CMAKE_CURRENT_BINARY_DIR}/tier-e-benchmark-dictionary-unique-live.mzsd" -cp "${AGENT_TEST_CLASSES}" AgentWorkload 0 platform 100
)
set_tests_properties(agent_phase_benchmark_unique_live PROPERTIES PASS_REGULAR_EXPRESSION "tier-e-benchmark: stack_transport=dictionary get_stack_trace_ns=[1-9][0-9]* raw_key_ns=[1-9][0-9]* raw_hash_ns=[1-9][0-9]* raw_equality_ns=[1-9][0-9]* generation_validation_ns=[1-9][0-9]* guard_validation_ns=[1-9][0-9]* canonical_cache_lock_wait_ns=[1-9][0-9]* logical_metadata_ns=[1-9][0-9]* .*serialization_ns=[1-9][0-9]* publication_bytes=[1-9][0-9]*")

add_test(
    NAME agent_live_publication_coalescing
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,mode=full-stream,live-definitions,definitions=${CMAKE_CURRENT_BINARY_DIR}/tier-e-live-coalescing.mzsd" -cp "${AGENT_TEST_CLASSES}" AgentWorkload 0 platform 3000
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
set_tests_properties(agent_phase_benchmark_inline_full PROPERTIES PASS_REGULAR_EXPRESSION "tier-e-benchmark: stack_transport=inline-per-invocation get_stack_trace_ns=[1-9][0-9]* raw_key_ns=[1-9][0-9]* raw_hash_ns=[1-9][0-9]* raw_equality_ns=[1-9][0-9]* generation_validation_ns=[1-9][0-9]* guard_validation_ns=[1-9][0-9]* canonical_cache_lock_wait_ns=[1-9][0-9]* logical_metadata_ns=[1-9][0-9]* .*serialization_ns=[1-9][0-9]* publication_bytes=[1-9][0-9]*")

add_test(
    NAME agent_phase_benchmark_inline_unique
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>=benchmark-phases,mode=unique-stack-syscall,stack-transport=inline-per-invocation,definitions=${CMAKE_CURRENT_BINARY_DIR}/tier-e-benchmark-inline-unique.mzsd" -cp "${AGENT_TEST_CLASSES}" AgentWorkload 0 platform 100
)
set_tests_properties(agent_phase_benchmark_inline_unique PROPERTIES PASS_REGULAR_EXPRESSION "tier-e-benchmark: stack_transport=inline-per-invocation get_stack_trace_ns=[1-9][0-9]* raw_key_ns=[1-9][0-9]* raw_hash_ns=[1-9][0-9]* raw_equality_ns=[1-9][0-9]* generation_validation_ns=[1-9][0-9]* guard_validation_ns=[1-9][0-9]* canonical_cache_lock_wait_ns=[1-9][0-9]* logical_metadata_ns=[1-9][0-9]* .*serialization_ns=[1-9][0-9]* publication_bytes=[1-9][0-9]*")

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

add_executable(live_stack_projection_test tests/live_stack_projection_test.cpp src/live_stack_checked_projection.cpp)
target_compile_options(live_stack_projection_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
add_test(NAME live_stack_projection_test COMMAND live_stack_projection_test)
