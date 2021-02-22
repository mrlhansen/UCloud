package dk.sdu.cloud.app.kubernetes.api

import dk.sdu.cloud.CommonErrorMessage
import dk.sdu.cloud.Roles
import dk.sdu.cloud.accounting.api.UCLOUD_PROVIDER
import dk.sdu.cloud.app.orchestrator.api.NetworkIPProvider
import dk.sdu.cloud.calls.*
import dk.sdu.cloud.service.PageV2
import dk.sdu.cloud.service.PaginationRequestV2Consistency
import dk.sdu.cloud.service.WithPaginationRequestV2

@TSNamespace("compute.ucloud.networkip")
object KubernetesNetworkIP : NetworkIPProvider(UCLOUD_PROVIDER)

data class K8NetworkStatus(val capacity: Long, val used: Long)
data class K8Subnet(val cidr: String)
typealias KubernetesIPMaintenanceCreateRequest = BulkRequest<K8Subnet>
typealias KubernetesIPMaintenanceCreateResponse = Unit

data class KubernetesIPMaintenanceBrowseRequest(
    override val itemsPerPage: Int? = null,
    override val next: String? = null,
    override val consistency: PaginationRequestV2Consistency? = null,
    override val itemsToSkip: Long? = null,
) : WithPaginationRequestV2
typealias KubernetesIPMaintenanceBrowseResponse = PageV2<K8Subnet>

typealias KubernetesIPMaintenanceRetrieveStatusRequest = Unit
typealias KubernetesIPMaintenanceRetrieveStatusResponse = K8NetworkStatus

@TSNamespace("compute.ucloud.networkip.maintenance")
object KubernetesNetworkIPMaintenance : CallDescriptionContainer("compute.networkip.ucloud.maintenance") {
    val baseContext = KubernetesNetworkIP.baseContext + "/maintenance"

    val create = call<KubernetesIPMaintenanceCreateRequest, KubernetesIPMaintenanceCreateResponse,
        CommonErrorMessage>("create") {
        httpCreate(baseContext, roles = Roles.PRIVILEGED)
    }

    val browse = call<KubernetesIPMaintenanceBrowseRequest, KubernetesIPMaintenanceBrowseResponse,
        CommonErrorMessage>("browse") {
        httpBrowse(baseContext, roles = Roles.PRIVILEGED)
    }

    val retrieveStatus = call<KubernetesIPMaintenanceRetrieveStatusRequest,
        KubernetesIPMaintenanceRetrieveStatusResponse, CommonErrorMessage>("retrieveStatus") {
        httpRetrieve(baseContext, roles = Roles.PRIVILEGED)
    }
}