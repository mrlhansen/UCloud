package dk.sdu.cloud.file.orchestrator.api

import dk.sdu.cloud.CommonErrorMessage
import dk.sdu.cloud.FindByStringId
import dk.sdu.cloud.PageV2
import dk.sdu.cloud.PaginationRequestV2Consistency
import dk.sdu.cloud.WithPaginationRequestV2
import dk.sdu.cloud.accounting.api.Product
import dk.sdu.cloud.accounting.api.ProductReference
import dk.sdu.cloud.accounting.api.providers.ProductSupport
import dk.sdu.cloud.accounting.api.providers.ResolvedSupport
import dk.sdu.cloud.accounting.api.providers.ResourceApi
import dk.sdu.cloud.accounting.api.providers.ResourceBrowseRequest
import dk.sdu.cloud.accounting.api.providers.ResourceControlApi
import dk.sdu.cloud.accounting.api.providers.ResourceProviderApi
import dk.sdu.cloud.accounting.api.providers.ResourceTypeInfo
import dk.sdu.cloud.provider.api.Resource
import dk.sdu.cloud.provider.api.ResourceAclEntry
import dk.sdu.cloud.provider.api.ResourceIncludeFlags
import dk.sdu.cloud.provider.api.ResourceOwner
import dk.sdu.cloud.provider.api.ResourcePermissions
import dk.sdu.cloud.provider.api.ResourceSpecification
import dk.sdu.cloud.provider.api.ResourceStatus
import dk.sdu.cloud.provider.api.ResourceUpdate
import dk.sdu.cloud.calls.*
import dk.sdu.cloud.provider.api.Permission
import dk.sdu.cloud.provider.api.Resources
import kotlinx.serialization.Serializable
import kotlin.reflect.typeOf

@Serializable
data class Share(
    override val id: String,
    override val specification: Spec,
    override val createdAt: Long,
    override val status: Status,
    override val updates: List<Update>,
    override val owner: ResourceOwner,
    override val permissions: ResourcePermissions?
) : Resource<Product.Storage, ShareSupport> {
    @Serializable
    data class Spec(
        val sharedWith: String,
        val sourceFilePath: String,
        val permissions: List<Permission>,
        override val product: ProductReference
    ) : ResourceSpecification

    @Serializable
    @UCloudApiOwnedBy(Shares::class)
    data class Update(
        val newState: State,
        val shareAvailableAt: String?,
        override val timestamp: Long,
        override val status: String?
    ) : ResourceUpdate

    @Serializable
    data class Status(
        val shareAvailableAt: String?,
        val state: State,
        override var resolvedSupport: ResolvedSupport<Product.Storage, ShareSupport>? = null,
        override var resolvedProduct: Product.Storage? = null,
    ) : ResourceStatus<Product.Storage, ShareSupport>

    enum class State {
        APPROVED,
        REJECTED,
        PENDING
    }
}

enum class ShareType {
    UCLOUD_MANAGED_COLLECTION
}

@Serializable
data class ShareSupport(
    val type: ShareType,
    override val product: ProductReference
): ProductSupport

@Serializable
data class ShareFlags(
    override val includeOthers: Boolean = false,
    override val includeUpdates: Boolean = false,
    override val includeSupport: Boolean = false,
    override val includeProduct: Boolean = false,
    override val filterCreatedBy: String? = null,
    override val filterCreatedAfter: Long? = null,
    override val filterCreatedBefore: Long? = null,
    override val filterProvider: String? = null,
    override val filterProductId: String? = null,
    override val filterProductCategory: String? = null,
    override val filterProviderIds: String? = null,
    val filterIngoing: Boolean? = null,
    val filterOriginalPath: String? = null,
    val filterRejected: String? = null,
    override val filterIds: String? = null,
    override val hideProductId: String? = null,
    override val hideProductCategory: String? = null,
    override val hideProvider: String? = null,
) : ResourceIncludeFlags

typealias SharesUpdatePermissionsRequest = BulkRequest<SharesUpdatePermissionsRequestItem>

@Serializable
data class SharesUpdatePermissionsRequestItem(
    val id: String,
    val permissions: List<Permission>
)

@Serializable
data class OutgoingShareGroup(
    val sourceFilePath: String,
    val storageProduct: ProductReference,
    val sharePreview: List<Preview>,
) {
    @Serializable
    data class Preview(
        val sharedWith: String,
        val permissions: List<Permission>,
        val state: Share.State,
        val shareId: String,
    )
}

@Serializable
data class SharesBrowseOutgoingRequest(
    override val itemsPerPage: Int? = null,
    override val next: String? = null,
    override val consistency: PaginationRequestV2Consistency? = null,
    override val itemsToSkip: Long? = null
) : WithPaginationRequestV2

object Shares : ResourceApi<Share, Share.Spec, Share.Update, ShareFlags, Share.Status,
        Product.Storage, ShareSupport>("shares") {
    @OptIn(ExperimentalStdlibApi::class)
    override val typeInfo = ResourceTypeInfo(
        Share.serializer(),
        typeOf<Share>(),
        Share.Spec.serializer(),
        typeOf<Share.Spec>(),
        Share.Update.serializer(),
        typeOf<Share.Update>(),
        ShareFlags.serializer(),
        typeOf<ShareFlags>(),
        Share.Status.serializer(),
        typeOf<Share.Status>(),
        ShareSupport.serializer(),
        typeOf<ShareSupport>(),
        Product.Storage.serializer(),
        typeOf<Product.Storage>(),
    )

    init {
        description = """Shares provide users a way of collaborating on individual folders in a personal workspaces.

${Resources.readMeFirst}

This feature is currently implemented for backwards compatibility with UCloud. We don't currently recommend
other providers implement this functionality. Nevertheless, we provide a few example to give you an idea of 
how to use this feature. We generally recommend that you use a full-blown project for collaboration.
        """
    }

    override fun documentation() {
        useCase(
            "complete",
            "Complete example",
            flow = {
                val alice = actor("alice", "A UCloud user named Alice")
                val bob = actor("bob", "A UCloud user named Bob")

                comment("""
                    In this example we will see Alice sharing a folder with Bob. Alice starts by creating a share. The
                    share references a UFile.
                """.trimIndent())

                val spec = Share.Spec(
                    "bob",
                    "/5123/work/my-project/my-collaboration",
                    listOf(Permission.EDIT),
                    ProductReference("share", "example-ssd", "example")
                )
                success(
                    create,
                    bulkRequestOf(spec),
                    BulkResponse(listOf(FindByStringId("6342"))),
                    alice
                )

                comment("""
                    This returns a new ID of the Share resource. Bob can now view this when browsing the ingoing shares.
                """.trimIndent())

                success(
                    browse,
                    ResourceBrowseRequest(
                        ShareFlags(filterIngoing = true),
                    ),
                    PageV2(
                        50,
                        listOf(
                            Share(
                                "6342",
                                spec,
                                1635151675465L,
                                Share.Status(
                                    null,
                                    Share.State.PENDING
                                ),
                                emptyList(),
                                ResourceOwner("alice", null),
                                ResourcePermissions(listOf(Permission.READ), null),
                            )
                        ),
                        null
                    ),
                    bob
                )

                comment("Bob now approves this share request")

                success(
                    approve,
                    bulkRequestOf(FindByStringId("6342")),
                    Unit,
                    bob
                )

                comment("And the file is now shared and available at the path /6412")

                success(
                    browse,
                    ResourceBrowseRequest(
                        ShareFlags(filterIngoing = true),
                    ),
                    PageV2(
                        50,
                        listOf(
                            Share(
                                "6342",
                                spec,
                                1635151675465L,
                                Share.Status(
                                    "/6412",
                                    Share.State.APPROVED
                                ),
                                emptyList(),
                                ResourceOwner("alice", null),
                                ResourcePermissions(listOf(Permission.READ), null),
                            )
                        ),
                        null
                    ),
                    bob
                )
            }
        )
    }

    val approve = call<BulkRequest<FindByStringId>, Unit, CommonErrorMessage>("approve") {
        httpUpdate(baseContext, "approve")
    }

    val reject = call<BulkRequest<FindByStringId>, Unit, CommonErrorMessage>("reject") {
        httpUpdate(baseContext, "reject")
    }

    val updatePermissions = call<SharesUpdatePermissionsRequest, Unit, CommonErrorMessage>("updatePermissions") {
        httpUpdate(baseContext, "permissions")
    }

    val browseOutgoing =
        call<SharesBrowseOutgoingRequest, PageV2<OutgoingShareGroup>, CommonErrorMessage>("browseOutgoing") {
            httpBrowse(baseContext, "outgoing")
        }


    override val create get() = super.create!!
    override val delete get() = super.delete!!
    override val search get() = super.search!!
}

object SharesControl : ResourceControlApi<Share, Share.Spec, Share.Update, ShareFlags, Share.Status,
        Product.Storage, ShareSupport>("shares") {
    @OptIn(ExperimentalStdlibApi::class)
    override val typeInfo = ResourceTypeInfo(
        Share.serializer(),
        typeOf<Share>(),
        Share.Spec.serializer(),
        typeOf<Share.Spec>(),
        Share.Update.serializer(),
        typeOf<Share.Update>(),
        ShareFlags.serializer(),
        typeOf<ShareFlags>(),
        Share.Status.serializer(),
        typeOf<Share.Status>(),
        ShareSupport.serializer(),
        typeOf<ShareSupport>(),
        Product.Storage.serializer(),
        typeOf<Product.Storage>(),
    )
}

open class SharesProvider(provider: String) : ResourceProviderApi<Share, Share.Spec, Share.Update, ShareFlags, Share.Status,
        Product.Storage, ShareSupport>("shares", provider) {
    @OptIn(ExperimentalStdlibApi::class)
    override val typeInfo = ResourceTypeInfo(
        Share.serializer(),
        typeOf<Share>(),
        Share.Spec.serializer(),
        typeOf<Share.Spec>(),
        Share.Update.serializer(),
        typeOf<Share.Update>(),
        ShareFlags.serializer(),
        typeOf<ShareFlags>(),
        Share.Status.serializer(),
        typeOf<Share.Status>(),
        ShareSupport.serializer(),
        typeOf<ShareSupport>(),
        Product.Storage.serializer(),
        typeOf<Product.Storage>(),
    )

    override val delete get() = super.delete!!
}