package dk.sdu.cloud.app.store

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.NamedType
import dk.sdu.cloud.accounting.util.IdCardService
import dk.sdu.cloud.accounting.util.ProjectCache
import dk.sdu.cloud.app.store.api.*
import dk.sdu.cloud.app.store.rpc.AppStoreController
import dk.sdu.cloud.app.store.services.*
import dk.sdu.cloud.app.store.services.Workflows
import dk.sdu.cloud.app.store.util.yamlMapper
import dk.sdu.cloud.auth.api.authenticator
import dk.sdu.cloud.calls.client.OutgoingHttpCall
import dk.sdu.cloud.micro.*
import dk.sdu.cloud.service.*
import dk.sdu.cloud.service.db.async.AsyncDBSessionFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlin.reflect.KClass
import kotlin.reflect.jvm.jvmName

class Server(override val micro: Micro) : CommonServer {
    override val log = logger()

    override fun start() {
        val db = AsyncDBSessionFactory(micro)
        val serviceClient = micro.authenticator.authenticateClient(OutgoingHttpCall)
        val distributedState = DistributedStateFactory(micro)
        val idCardService = IdCardService(db, micro.backgroundScope, serviceClient)

        val data = CatalogData(db)
        val projectCache = ProjectCache(distributedState, db)
        val catalog = Catalog(projectCache, micro.backgroundScope, serviceClient, db)
        val studio = Studio(db, projectCache, data, serviceClient)
        val importer = ImportExport(micro.developmentModeEnabled, data, studio)
        val workflows = Workflows(db, idCardService)

        configureJackson(ApplicationParameter::class, yamlMapper)

        runBlocking {
            data.reloadData()
        }

        configureControllers(
            AppStoreController(importer, data, catalog, studio, workflows, db, micro.developmentModeEnabled),
        )
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
abstract class SealedClassMixin

private fun configureJackson(klass: KClass<*>, mapper: ObjectMapper) {
    val javaClass = klass.java

    if (klass.isSealed) {
        mapper.addMixIn(javaClass, SealedClassMixin::class.java)
        klass.sealedSubclasses.forEach {
            val name = it.annotations.filterIsInstance<SerialName>().firstOrNull()?.value ?: it.qualifiedName
            ?: it.jvmName
            mapper.registerSubtypes(NamedType(it.java, name))
        }
    }
}
