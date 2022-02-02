package dk.sdu.cloud.file.ucloud.services

import dk.sdu.cloud.ActorAndProject
import dk.sdu.cloud.FindByStringId
import dk.sdu.cloud.accounting.api.ProductReference
import dk.sdu.cloud.accounting.api.UCLOUD_PROVIDER
import dk.sdu.cloud.calls.*
import dk.sdu.cloud.calls.client.*
import dk.sdu.cloud.file.orchestrator.api.*
import dk.sdu.cloud.file.ucloud.LocalSyncthingDevice
import dk.sdu.cloud.file.ucloud.api.SyncFolderBrowseItem
import dk.sdu.cloud.provider.api.Permission
import dk.sdu.cloud.provider.api.ResourceUpdateAndId
import dk.sdu.cloud.service.SimpleCache
import dk.sdu.cloud.service.Time
import dk.sdu.cloud.service.db.async.*
import dk.sdu.cloud.sync.mounter.api.*
import java.io.File

object SyncFoldersTable : SQLTable("sync_folders") {
    val id = long("id", notNull = true)
    val device = varchar("device_id", 64, notNull = true)
    val path = text("path", notNull = true)
    val syncType = varchar("sync_type", 20, notNull = true)
    val user = text("user_id", notNull = true)
}

object SyncDevicesTable : SQLTable("sync_devices") {
    val id = long("id", notNull = true)
    val device = varchar("device_id", 64, notNull = true)
    val user = text("user_id", notNull = true)
}

class SyncService(
    private val syncthing: SyncthingClient,
    private val db: AsyncDBSessionFactory,
    private val authenticatedClient: AuthenticatedClient,
    private val cephStats: CephFsFastDirectoryStats,
    private val pathConverter: PathConverter,
    private val mounterClient: AuthenticatedClient,
) {
    private val folderDeviceCache = SimpleCache<Unit, LocalSyncthingDevice> {
        db.withSession { session ->
            syncthing.config.devices.associateWith { device ->
                session.sendPreparedStatement(
                    {
                        setParameter("device", device.id)
                    },
                    """
                        select path
                        from file_ucloud.sync_folders
                        where device_id = :device
                    """
                ).rows.map { it.getField(SyncFoldersTable.path) }
            }.filter { it.value.size < 1000 }
                .minByOrNull { (_, folders) ->
                    folders.sumOf { folder ->
                        cephStats.getRecursiveSize(
                            pathConverter.ucloudToInternal(UCloudFile.create(folder))
                        ) ?: 0
                    }
                }?.key
        }
    }

    private suspend fun chooseFolderDevice(session: AsyncDBConnection): LocalSyncthingDevice {
        return folderDeviceCache.get(Unit)
            ?: throw RPCException.fromStatusCode(HttpStatusCode.NotFound, "Syncthing device not found")
    }

    suspend fun addFolders(request: BulkRequest<SyncFolder>): BulkResponse<FindByStringId?> {
        if (!syncthing.config.userWhiteList.containsAll(request.items.map { it.owner.createdBy })) {
            throw RPCException.fromStatusCode(HttpStatusCode.Unauthorized)
        }

        val affectedDevices: MutableSet<LocalSyncthingDevice> = mutableSetOf()
        val remoteDevices: MutableList<LocalSyncthingDevice> = mutableListOf()

        val affectedRows: Long = db.withSession { session ->
            request.items.forEach { folder ->
                val internalFile = pathConverter.ucloudToInternal(UCloudFile.create(folder.specification.path))

                if (!File(internalFile.path).exists() || !File(internalFile.path).isDirectory) {
                    throw RPCException.fromStatusCode(HttpStatusCode.NotFound)
                }

                if (cephStats.getRecursiveFileCount(internalFile) > 1_000_000) {
                    throw RPCException(
                        "Number of files in directory exceeded for synchronization",
                        HttpStatusCode.Forbidden
                    )
                }

                val device = chooseFolderDevice(session)
                affectedDevices.add(device)

                SyncFolderControl.update.call(
                    bulkRequestOf(
                        ResourceUpdateAndId(
                            folder.id,
                            SyncFolder.Update(
                                Time.now(),
                                "Updated synchronized folder",
                                remoteDeviceId = device.id,
                                permission = folder.status.permission
                            )
                        )
                    ),
                    authenticatedClient
                )

                Mounts.mount.call(
                    MountRequest(listOf(MountFolder(folder.id.toLong(), internalFile.path))),
                    mounterClient,
                ).orRethrowAs {
                    throw RPCException.fromStatusCode(
                        HttpStatusCode.InternalServerError,
                        "Failed to prepare folder for synchronization"
                    )
                }

                remoteDevices.add(device)
            }

            val affectedRows = session.sendPreparedStatement(
                {
                    setParameter("ids", request.items.map { folder -> folder.id.toLong() })
                    setParameter("remote_devices", remoteDevices.map { it.id })
                    setParameter("paths", request.items.map { folder -> folder.specification.path })
                    setParameter("users", request.items.map { folder -> folder.owner.createdBy })
                    setParameter("type", request.items.map { folder ->
                        when (folder.status.permission) {
                            Permission.READ -> SynchronizationType.SEND_ONLY.name
                            Permission.EDIT -> SynchronizationType.SEND_RECEIVE.name
                            Permission.ADMIN -> SynchronizationType.SEND_RECEIVE.name
                            Permission.PROVIDER -> SynchronizationType.SEND_ONLY.name
                        }
                    })
                },
                """
                    insert into file_ucloud.sync_folders(id, device_id, path, user_id, sync_type)
                    values (unnest(:ids::bigint[]), unnest(:remote_devices::text[]), unnest(:paths::text[]), 
                            unnest(:users::text[]), unnest(:type::text[])) 
                    on conflict do nothing
                """
            ).rowsAffected

            affectedDevices.forEach { device ->
                // Mounter is ready when all folders added to syncthing on that device is mounted.
                // Syncthing is ready when it's accessible.

                var retries = 0
                while (true) {
                    if (retries == 3) {
                        throw RPCException(
                            "The synchronization feature is offline. Please try again later.",
                            HttpStatusCode.ServiceUnavailable
                        )
                    }

                    try {
                        val mounter = Mounts.ready.call(Unit, mounterClient)
                        val syncthingReady = syncthing.isReady(device)

                        if (
                            mounter.statusCode != HttpStatusCode.OK ||
                            !mounter.orThrow().ready ||
                            syncthingReady == null ||
                            !syncthingReady
                        ) {
                            retries++
                        } else {
                            break
                        }
                    } catch (ex: Throwable) {
                        retries++
                    }
                }
            }

            affectedRows
        }

        if (affectedRows > 0) {
            try {
                syncthing.writeConfig(affectedDevices.toList())
            } catch (ex: Throwable) {
                request.items.forEach { folder ->
                    Mounts.unmount.call(
                        UnmountRequest(listOf(MountFolderId(folder.id.toLong()))),
                        mounterClient
                    )
                }

                db.withSession { session ->
                    session.sendPreparedStatement(
                        {
                            setParameter("ids", request.items.map { folder -> folder.id.toLong() })
                        },
                        """
                            delete from file_ucloud.sync_folders
                            where id in (select unnest(:ids::bigint[]))
                        """
                    )
                }
            }
        }

        return BulkResponse(emptyList())
    }

    suspend fun removeFolders(ids: List<Long>) {
        data class DeletedFolder(
            val id: Long,
            val path: String,
            val localDevice: LocalSyncthingDevice,
            val syncType: SynchronizationType,
            val userId: String
        )

        val deleted = db.withSession { session ->
            val deleted = session.sendPreparedStatement(
                {
                    setParameter("ids", ids)
                },
                """
                    delete from file_ucloud.sync_folders
                    where id in (select unnest(:ids::bigint[]))
                    returning id, device_id, path, sync_type, user_id 
                """
            ).rows.mapNotNull {
                val id = it.getField(SyncFoldersTable.id)
                val deviceId = it.getField(SyncFoldersTable.device)
                val path = it.getField(SyncFoldersTable.path)
                val syncType = it.getField(SyncFoldersTable.syncType)
                val userId = it.getField(SyncFoldersTable.user)

                val device = syncthing.config.devices.find { it.id == deviceId }
                if (device != null) {
                    DeletedFolder(id, path, device, SynchronizationType.valueOf(syncType), userId)
                } else {
                    null
                }
            }

            deleted.groupBy { it.localDevice }.forEach { deviceFolders ->
                Mounts.unmount.call(
                    UnmountRequest(deviceFolders.value.map { MountFolderId(it.id) }),
                    mounterClient
                ).orRethrowAs { throw RPCException.fromStatusCode(HttpStatusCode.InternalServerError) }
            }

            deleted
        }

        try {
            syncthing.writeConfig(deleted.map { it.localDevice })
        } catch (ex: Throwable) {
            deleted.groupBy { it.localDevice }.forEach { deviceFolders ->
                Mounts.mount.call(
                    MountRequest(
                        deviceFolders.value.map {
                            val internalFile = pathConverter.ucloudToInternal(UCloudFile.create(it.path))
                            MountFolder(it.id, internalFile.path)
                        }
                    ),
                    mounterClient
                )
            }

            db.withSession { session ->
                session.sendPreparedStatement(
                    {
                        setParameter("ids", deleted.map { it.id })
                        setParameter("devices", deleted.map { it.localDevice.id })
                        setParameter("paths", deleted.map { it.path })
                        setParameter("users", deleted.map { it.userId })
                        setParameter("types", deleted.map { it.syncType.name })
                    },
                    """
                        insert into file_ucloud.sync_folders(
                            id, 
                            device_id, 
                            path,
                            user_id,
                            sync_type
                        ) values (
                            unnest(:ids::bigint[]),
                            unnest(:devices::text[]),
                            unnest(:paths::text[]),
                            unnest(:users::text[]),
                            unnest(:types::text[])
                        ) on conflict do nothing
                    """
                )
            }
        }
    }

    suspend fun browseFolders(
        device: String
    ): List<SyncFolderBrowseItem> {
        return db.withSession { session ->
            session.sendPreparedStatement(
                {
                    setParameter("device", device)
                },
                """
                    select id, path, sync_type
                    from file_ucloud.sync_folders
                    where device_id = :device
                """
            ).rows.map { folder ->
                SyncFolderBrowseItem(
                    folder.getField(SyncFoldersTable.id),
                    pathConverter.ucloudToInternal(UCloudFile.create(folder.getField(SyncFoldersTable.path))).path,
                    SynchronizationType.valueOf(folder.getField(SyncFoldersTable.syncType))
                )
            }
        }
    }

    suspend fun addDevices(devices: BulkRequest<SyncDevice>): BulkResponse<FindByStringId?> {
        for (item in devices.items) {
            if (!item.specification.deviceId.matches(deviceIdRegex)) {
                throw RPCException("Invalid device ID: ${item.specification.deviceId}", HttpStatusCode.BadRequest)
            }
        }

        val affectedRows = db.withSession { session ->
            devices.items.sumOf { device ->
                if (syncthing.config.devices.any { it.id == device.id }) {
                    throw RPCException.fromStatusCode(HttpStatusCode.BadRequest)
                }

                session.sendPreparedStatement(
                    {
                        setParameter("id", device.id)
                        setParameter("device", device.specification.deviceId)
                        setParameter("user", device.owner.createdBy)
                    },
                    """
                        insert into file_ucloud.sync_devices(
                            id,
                            device_id,
                            user_id
                        ) values (
                            :id,
                            :device,
                            :user
                        )
                    """
                ).rowsAffected
            }
        }

        if (affectedRows > 0) {
            try {
                syncthing.writeConfig()
            } catch (ex: Throwable) {
                db.withSession { session ->
                    session.sendPreparedStatement(
                        {
                            setParameter("ids", devices.items.map { it.id })
                        },
                        """
                            delete from file_ucloud.sync_devices
                            where id in (select unnest(:ids::bigint[]))
                        """
                    )
                }
                throw RPCException("Invalid device ID", HttpStatusCode.BadRequest)
            }
        }

        return BulkResponse(emptyList())
    }

    suspend fun removeDevices(devices: BulkRequest<SyncDevice>) {
        data class DeletedDevice(
            val id: Long,
            val deviceId: String,
            val userId: String
        )

        val deleted = db.withSession { session ->
            session.sendPreparedStatement(
                {
                    setParameter("ids", devices.items.map { it.id })
                },
                """
                    delete from file_ucloud.sync_devices
                    where id in (select unnest(:ids::bigint[]))
                    returning id, device_id, user_id
                """
            ).rows.mapNotNull {
                DeletedDevice(
                    it.getField(SyncDevicesTable.id),
                    it.getField(SyncDevicesTable.device),
                    it.getField(SyncDevicesTable.user)
                )
            }
        }

        if (deleted.isNotEmpty()) {
            try {
                syncthing.writeConfig()
            } catch (ex: Throwable) {
                db.withSession { session ->
                    session.sendPreparedStatement(
                        {
                            setParameter("ids", deleted.map { it.id })
                            setParameter("deviceIds", deleted.map { it.deviceId })
                            setParameter("userIds", deleted.map { it.userId })
                        },
                        """
                            insert into file_ucloud.sync_devices(
                                id,
                                device_id,
                                user_id
                            ) values (
                                unnest(:ids::bigint[]),
                                unnest(:deviceIds::text[]),
                                unnest(:userIds::text[])
                            )
                        """
                    )
                }
            }
        }
    }

    suspend fun updatePermissions(folders: BulkRequest<SyncFolderPermissionsUpdatedRequestItem>): BulkResponse<Unit?> {
        db.withSession { session ->
            session.sendPreparedStatement(
                {
                    folders.items.split {
                        into("ids") { it.resourceId.toLong() }
                        into("permissions") {
                            when (it.newPermission) {
                                Permission.READ -> SynchronizationType.SEND_ONLY.name
                                Permission.EDIT -> SynchronizationType.SEND_RECEIVE.name
                                Permission.ADMIN -> SynchronizationType.SEND_RECEIVE.name
                                Permission.PROVIDER -> SynchronizationType.SEND_ONLY.name
                            }
                        }
                    }
                },
                """
                    update file_ucloud.sync_folders f
                    set sync_type = updates.sync_type
                    from (
                        select
                            unnest(:ids::bigint[]) id, 
                            unnest(:permissions::text[]) sync_type
                    ) updates
                    where
                        f.id = updates.id
                """
            )
        }

        if (folders.items.isNotEmpty()) syncthing.writeConfig()

        SyncFolderControl.update.call(
            BulkRequest(folders.items.map {
                ResourceUpdateAndId(
                    it.resourceId,
                    SyncFolder.Update(
                        permission = it.newPermission
                    )
                )
            }),
            authenticatedClient
        )

        return BulkResponse(folders.items.map { })
    }

    companion object {
        // NOTE(Dan): This is truly a beautiful regex. I refuse to write something better (unless someone has a good
        // reason).
        private val deviceIdRegex =
            Regex("""[\w\d][\w\d][\w\d][\w\d][\w\d][\w\d][\w\d]-[\w\d][\w\d][\w\d][\w\d][\w\d][\w\d][\w\d]-[\w\d][\w\d][\w\d][\w\d][\w\d][\w\d][\w\d]-[\w\d][\w\d][\w\d][\w\d][\w\d][\w\d][\w\d]-[\w\d][\w\d][\w\d][\w\d][\w\d][\w\d][\w\d]-[\w\d][\w\d][\w\d][\w\d][\w\d][\w\d][\w\d]-[\w\d][\w\d][\w\d][\w\d][\w\d][\w\d][\w\d]-[\w\d][\w\d][\w\d][\w\d][\w\d][\w\d][\w\d]""")
    }
}

val syncProducts = listOf(
    SyncFolderSupport(ProductReference("u1-sync", "u1-sync", UCLOUD_PROVIDER)),
)