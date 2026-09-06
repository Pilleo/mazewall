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

add_executable(marker_export_test tests/marker_export_test.cpp)
target_compile_options(marker_export_test PRIVATE -Wall -Wextra -Werror -UNDEBUG)
target_link_libraries(marker_export_test PRIVATE ${CMAKE_DL_LIBS})
add_test(NAME marker_export_test COMMAND marker_export_test $<TARGET_FILE:mazewall_stack_agent>)

set(AGENT_TEST_CLASSES "${CMAKE_CURRENT_BINARY_DIR}/agent-test-classes")
add_custom_command(
    OUTPUT "${AGENT_TEST_CLASSES}/AgentWorkload.class"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${AGENT_TEST_CLASSES}"
    COMMAND "${Java_JAVAC_EXECUTABLE}" -d "${AGENT_TEST_CLASSES}" "${CMAKE_CURRENT_SOURCE_DIR}/tests/AgentWorkload.java"
    DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/tests/AgentWorkload.java"
    VERBATIM
)
add_custom_target(agent_workload_test_classes ALL DEPENDS "${AGENT_TEST_CLASSES}/AgentWorkload.class")
add_test(
    NAME agent_smoke_test
    COMMAND "${Java_JAVA_EXECUTABLE}" "-agentpath:$<TARGET_FILE:mazewall_stack_agent>" -cp "${AGENT_TEST_CLASSES}" AgentWorkload
)
set_tests_properties(
    agent_smoke_test
    PROPERTIES PASS_REGULAR_EXPRESSION "proxied-binds=[1-9][0-9]* stack-captures=[1-9][0-9]*"
)

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
