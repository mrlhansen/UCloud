package dk.sdu.cloud.app.aau.rpc

import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import dk.sdu.cloud.Role
import dk.sdu.cloud.SecurityPrincipal
import dk.sdu.cloud.accounting.api.Product
import dk.sdu.cloud.accounting.api.Products
import dk.sdu.cloud.accounting.api.RetrieveAllFromProviderRequest
import dk.sdu.cloud.accounting.api.UCLOUD_PROVIDER
import dk.sdu.cloud.app.aau.services.ResourceCache
import dk.sdu.cloud.app.kubernetes.api.AauCompute
import dk.sdu.cloud.app.kubernetes.api.AauComputeMaintenance
import dk.sdu.cloud.app.orchestrator.api.*
import dk.sdu.cloud.app.store.api.ToolBackend
import dk.sdu.cloud.calls.RPCException
import dk.sdu.cloud.calls.bulkRequestOf
import dk.sdu.cloud.calls.client.AuthenticatedClient
import dk.sdu.cloud.calls.client.call
import dk.sdu.cloud.calls.client.orThrow
import dk.sdu.cloud.calls.server.RpcServer
import dk.sdu.cloud.calls.server.sendWSMessage
import dk.sdu.cloud.defaultMapper
import dk.sdu.cloud.provider.api.ManifestFeatureSupport
import dk.sdu.cloud.service.Controller
import dk.sdu.cloud.service.SimpleCache
import dk.sdu.cloud.slack.api.SendSupportRequest
import dk.sdu.cloud.slack.api.SlackDescriptions
import io.ktor.http.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class ComputeController(
    private val serviceClient: AuthenticatedClient,
    private val resourceCache: ResourceCache,
    private val devMode: Boolean,
) : Controller {
    override fun configure(rpcServer: RpcServer) = with(rpcServer) {
        implement(AauCompute.create) {
            request.items.forEach { req ->
                val resources = resourceCache.findResources(req)
                val application = resources.application
                val tool = application.invocation.tool.tool!!

                if (tool.description.backend != ToolBackend.VIRTUAL_MACHINE) {
                    throw RPCException("Unsupported application", HttpStatusCode.BadRequest)
                }
            }

            request.items.forEach { req ->
                val resources = resourceCache.findResources(req)
                val application = resources.application
                val tool = application.invocation.tool.tool!!

                sendMessage(
                    req.id,
                    buildString {
                        appendLine("```")
                        appendLine("VM creation request")
                        appendLine("-------------------")
                        appendLine()
                        appendLine("Request ID: ${req.id}")
                        appendLine("Owner (username): ${req.owner.createdBy}")
                        appendLine("Owner (project): ${req.owner.project}")
                        appendLine("Base image: ${tool.description.image}")
                        appendLine("Machine template: " +
                            defaultMapper.writer(DefaultPrettyPrinter()).writeValueAsString(resources.product))
                        @Suppress("DEPRECATION")
                        appendLine("Total grant allocation: " +
                            "${req.billing.__creditsAllocatedToWalletDoNotDependOn__ / 1_000_000} DKK")
                        appendLine("Request parameters:")
                        req.specification.parameters?.forEach { (p, v) ->
                            appendLine("\t$p: $v")
                        }
                        appendLine("```")
                    }
                )
            }

            JobsControl.update.call(
                bulkRequestOf(
                    request.items.map { req ->
                        JobsControlUpdateRequestItem(
                            req.id,
                            JobState.IN_QUEUE,
                            "A request has been submitted and is now awaiting approval by a system administrator. " +
                                "This might take a few business days."
                        )
                    }
                ),
                serviceClient
            ).orThrow()

            ok(Unit)
        }

        implement(AauCompute.delete) {
            request.items.forEach { req ->
                val resources = resourceCache.findResources(req)
                val application = resources.application
                val tool = application.invocation.tool.tool!!

                sendMessage(
                    req.id,
                    buildString {
                        appendLine("```")
                        appendLine("VM deletion request")
                        appendLine("-------------------")
                        appendLine()
                        appendLine("Request ID: ${req.id}")
                        appendLine("Owner (username): ${req.owner.createdBy}")
                        appendLine("Owner (project): ${req.owner.project}")
                        appendLine("Base image: ${tool.description.image}")
                        appendLine("Machine template: " +
                            defaultMapper.writer(DefaultPrettyPrinter()).writeValueAsString(resources.product))
                        appendLine("```")
                    }
                )
            }

            JobsControl.update.call(
                bulkRequestOf(
                    request.items.map { req ->
                        JobsControlUpdateRequestItem(
                            req.id,
                            JobState.IN_QUEUE,
                            "A request for deletion has been submitted and is now awaiting action by a system administrator. " +
                                "This might take a few business days."
                        )
                    }
                ),
                serviceClient
            ).orThrow()

            ok(Unit)
        }

        implement(AauComputeMaintenance.sendUpdate) {
            JobsControl.update.call(
                bulkRequestOf(request.items.map { req ->
                    JobsControlUpdateRequestItem(req.id,
                        req.newState,
                        req.update)
                }),
                serviceClient
            ).orThrow()

            ok(Unit)
        }

        implement(AauComputeMaintenance.retrieve) {
            ok(JobsControl.retrieve.call(
                JobsControlRetrieveRequest(request.id, includeProduct = true, includeApplication = true),
                serviceClient
            ).orThrow())
        }

        implement(AauCompute.retrieveProductsTemporary) {
            ok(retrieveProductsTemporary())
        }

        implement(AauCompute.follow) {
                sendWSMessage(ComputeFollowResponse("id", -1, null, null))
                sendWSMessage(ComputeFollowResponse(
                    "id",
                    0,
                    "Please see the 'Messages' panel for how to access your machine",
                    null
                ))
            while (currentCoroutineContext().isActive) {
                delay(1000)
            }
            ok(ComputeFollowResponse("", 0, "Please see the 'Messages' panel for how to access your machine", null))
        }

        implement(AauCompute.extend) {
            ok(Unit)
        }

        implement(AauCompute.openInteractiveSession) {
            throw RPCException.fromStatusCode(HttpStatusCode.BadRequest)
        }

        implement(AauCompute.retrieveUtilization) {
            ok(ComputeUtilizationResponse(CpuAndMemory(100.0, 100L), CpuAndMemory(0.0, 0L), QueueStatus(0, 0)))
        }

        implement(AauCompute.verify) {
            ok(Unit)
        }

        implement(AauCompute.suspend) {
            throw RPCException.fromStatusCode(HttpStatusCode.BadRequest)
        }

        return@with
    }

    private suspend fun sendMessage(id: String, message: String) {
        if (devMode) {
            println(message)
        } else {
            SlackDescriptions.sendSupport.call(
                SendSupportRequest(
                    id,
                    SecurityPrincipal(
                        username = "_UCloud",
                        role = Role.SERVICE,
                        firstName = "UCloud",
                        lastName = "",
                        uid = 0L
                    ),
                    "UCloud",
                    "AAU Virtual Machine [${id.substringBefore('-').toUpperCase()}]",
                    message
                ),
                serviceClient
            ).orThrow()
        }
    }

    private val productCache = SimpleCache<Unit, List<Product.Compute>>(lookup = {
        Products.retrieveAllFromProvider.call(
            RetrieveAllFromProviderRequest("aau"),
            serviceClient
        ).orThrow().filterIsInstance<Product.Compute>()
    })

    suspend fun retrieveProductsTemporary(): ComputeRetrieveProductsTemporaryResponse {
        return ComputeRetrieveProductsTemporaryResponse(productCache.get(Unit)?.map {
            ComputeTemporaryProductSupport(
                it,
                ManifestFeatureSupport.Compute(
                    ManifestFeatureSupport.Compute.Docker(
                        enabled = false,
                    ),
                    ManifestFeatureSupport.Compute.VirtualMachine(
                        enabled = true,
                    )
                )
            )
        } ?: emptyList())
    }
}