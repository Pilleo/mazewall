package io.mazewall.portal.codegen

import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/** Completion marker for portal generation and configured worker-artifact validation. */
public open class PortalCheckTask : DefaultTask() {
    @TaskAction
    public fun verify() {
        // Dependencies perform validation without loading or constructing guest code.
    }
}

/** Prints stable configured service metadata without executing worker code. */
public abstract class PortalDescribeTask : DefaultTask() {
    @get:Input
    public abstract val registrations: ListProperty<PortalServiceRegistration>

    @TaskAction
    public fun describe() {
        registrations
            .get()
            .sortedBy { it.serviceId }
            .forEach { registration ->
                logger.lifecycle(
                    "portal service=${registration.serviceId} contract=${registration.contractName} " +
                        "implementation=${registration.implementationName} group=${registration.isolationGroup} " +
                        "mode=${registration.instanceMode} profile=${registration.policyProfile} artifact=${registration.workerArtifact}",
                )
            }
    }
}
