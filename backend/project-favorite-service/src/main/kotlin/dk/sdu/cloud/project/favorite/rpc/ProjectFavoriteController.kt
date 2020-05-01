package dk.sdu.cloud.project.favorite.rpc

import dk.sdu.cloud.service.Controller
import dk.sdu.cloud.calls.server.RpcServer
import dk.sdu.cloud.calls.server.securityPrincipal
import dk.sdu.cloud.project.favorite.api.ProjectFavoriteDescriptions
import dk.sdu.cloud.project.favorite.services.ProjectFavoriteService
import dk.sdu.cloud.service.Loggable
import dk.sdu.cloud.service.NormalizedPaginationRequest

class ProjectFavoriteController(
    private val projectFavoriteService: ProjectFavoriteService
) : Controller {
    override fun configure(rpcServer: RpcServer): Unit = with(rpcServer) {
        implement(ProjectFavoriteDescriptions.toggleFavorite) {
            val projectID = request.projectID
            val user = ctx.securityPrincipal
            projectFavoriteService.toggleFavorite(projectID, user)
            ok(Unit)
        }

        implement(ProjectFavoriteDescriptions.listFavorites) {
            val user = ctx.securityPrincipal
            ok(projectFavoriteService.listFavorites(
                user,
                NormalizedPaginationRequest(request.itemsPerPage, request.page)
            ))
        }

        return@configure
    }

    companion object : Loggable {
        override val log = logger()
    }
}