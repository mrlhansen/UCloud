package dk.sdu.cloud.accounting.services.accounting

import dk.sdu.cloud.accounting.api.*
import dk.sdu.cloud.calls.HttpStatusCode
import dk.sdu.cloud.calls.RPCException
import dk.sdu.cloud.service.Time
import dk.sdu.cloud.service.db.async.DBContext
import dk.sdu.cloud.service.db.async.sendPreparedStatement
import dk.sdu.cloud.service.db.async.withSession

interface AccountingPersistence {
    suspend fun initialize()
    suspend fun flushChanges()
    suspend fun loadOldData(system: AccountingSystem)
}

object FakeAccountingPersistence : AccountingPersistence {
    override suspend fun initialize() {}
    override suspend fun flushChanges() {}
    override suspend fun loadOldData(system: AccountingSystem) {}
}

class RealAccountingPersistence(private val db: DBContext) : AccountingPersistence {
    private var nextSynchronization = 0L
    private var didChargeOldData = false

    override suspend fun initialize() {
        val now = Time.now()
        if (now < nextSynchronization) return
        db.withSession { session ->
            // Create walletOwners
            session.sendPreparedStatement(
                {},
                """
                    select
                        id,
                        coalesce(username, project_id)
                    from accounting.wallet_owner
                """
            ).rows.forEach {
                val id = it.getLong(0)!!.toInt()
                val reference = it.getString(1)!!

                val owner = InternalOwner(
                    id,
                    reference,
                    false
                )
                ownersByReference[reference] = owner
                ownersById[id] = owner
            }

            // Create allocations and groups
            session.sendPreparedStatement(
                {},
                """
                    select 
                        walloc.id, 
                        ag.associated_wallet,
                        ag.parent_wallet,
                        walloc.quota,
                        provider.timestamp_to_unix(walloc.allocation_start_time)::bigint, 
                        provider.timestamp_to_unix(walloc.allocation_end_time)::bigint, 
                        walloc.retired, 
                        walloc.retired_usage,
                        walloc.granted_in,
                        ag.id,
                        ag.tree_usage,
                        ag.retired_tree_usage
                    from
                        accounting.allocation_groups ag join   
                        accounting.wallet_allocations_v2 walloc on ag.id = walloc.associated_allocation_group
                """
            ).rows.forEach {
                val allocationId = it.getLong(0)!!.toInt()
                val associatedWallet = it.getLong(1)!!.toInt()
                val parentWallet = it.getLong(2)?.toInt() ?: 0
                val quota = it.getLong(3)!!
                val startTime = it.getLong(4)!!
                val endTime = it.getLong(5)!!
                val retired = it.getBoolean(6)!!
                val retiredUsage = it.getLong(7)!!
                val grantedIn = it.getLong(8)

                val allocation = InternalAllocation(
                    id = allocationId,
                    belongsTo = associatedWallet,
                    parentWallet = parentWallet,
                    quota = quota,
                    start = startTime,
                    end = endTime,
                    retired = retired,
                    retiredUsage = retiredUsage,
                    grantedIn = grantedIn,
                    isDirty = false
                )

                allocations[allocationId] = allocation

                val groupId = it.getLong(9)!!.toInt()
                val treeUsage = it.getLong(10)!!
                val retiredTreeUsage = it.getLong(11)!!

                val group = allocationGroups[groupId]
                if (group != null) {
                    allocationGroups[groupId]!!.allocationSet[allocation.id] = !allocation.retired
                } else {
                    val newGroup = InternalAllocationGroup(
                        id = groupId,
                        associatedWallet = associatedWallet,
                        parentWallet = parentWallet,
                        treeUsage = treeUsage,
                        retiredTreeUsage = retiredTreeUsage,
                        earliestExpiration = 0L,
                        allocationSet = HashMap(),
                        isDirty = false
                    )
                    newGroup.allocationSet[allocation.id] = !allocation.retired
                    allocationGroups[groupId] = newGroup
                }
            }

            //Set earliestExpiration for each allocationGroup
            allocationGroups.forEach { (groupId, group) ->
                val allocationsIds = group.allocationSet.map { it.key }
                var earliestExpiration = Long.MAX_VALUE
                allocationsIds.forEach { id ->
                    val alloc = allocations[id] ?: error("Allocation disappeared???")
                    val endTime = alloc.end
                    if (endTime < earliestExpiration && endTime >= Time.now()) {
                        earliestExpiration = endTime
                    }
                }
                allocationGroups[groupId]!!.earliestExpiration = earliestExpiration
            }

            val productCategories = HashMap<Long, ProductCategory>()
            session.sendPreparedStatement(
                {},
                """
                    select 
                        pc.id, 
                        category, 
                        provider, 
                        product_type, 
                        name, 
                        name_plural, 
                        floating_point, 
                        display_frequency_suffix,
                        accounting_frequency,
                        free_to_use,
                        allow_sub_allocations
                    from
                        accounting.product_categories pc
                        join accounting.accounting_units au on au.id = pc.accounting_unit  
                """
            ).rows.forEach {
                val id = it.getLong(0)!!
                val productType = ProductType.valueOf(it.getString(3)!!)
                val accountingUnit = AccountingUnit(
                    it.getString(4)!!,
                    it.getString(5)!!,
                    it.getBoolean(6)!!,
                    it.getBoolean(7)!!
                )
                val accountingFrequency = AccountingFrequency.fromValue(it.getString(8)!!)
                val pc = ProductCategory(
                    name = it.getString(1)!!,
                    provider = it.getString(2)!!,
                    productType = productType,
                    accountingUnit = accountingUnit,
                    accountingFrequency = accountingFrequency,
                    freeToUse = it.getBoolean(9)!!,
                    allowSubAllocations = it.getBoolean(10)!!
                )
                productCategories[id] = pc
            }

            // Create Wallets
            session.sendPreparedStatement(
                {},
                """
                    select 
                        id,
                        wallet_owner,
                        product_category,
                        local_usage,
                        local_retired_usage,
                        excess_usage,
                        total_allocated,
                        total_retired_allocated
                    from 
                        accounting.wallets_v2
                """
            ).rows.forEach { row ->
                val id = row.getLong(0)!!.toInt()
                val owner = row.getLong(1)!!.toInt()
                val productCategory = productCategories[row.getLong(2)!!]
                val localUsage = row.getLong(3)!!
                val localRetiredUsage = row.getLong(4)!!
                val excessUsage = row.getLong(5)!!
                val totalAllocated = row.getLong(6)!!
                val totalRetiredAllocated = row.getLong(7)!!

                val allocGroups = allocationGroups.filter { it.value.associatedWallet == id }
                val allocationByParent = HashMap<Int, InternalAllocationGroup>()
                allocGroups.forEach { (_, allocationGroup) ->
                    allocationByParent[allocationGroup.parentWallet] = allocationGroup
                }

                val childrenAllocGroups = allocationGroups.filter { it.value.parentWallet == id }
                val childrenRetiredUsage = HashMap<Int, Long>()
                val childrenUsage = HashMap<Int, Long>()

                childrenAllocGroups.forEach { (_, group) ->
                    val treeUsage = group.treeUsage
                    val retriedUsage = group.retiredTreeUsage
                    childrenUsage[group.associatedWallet] = treeUsage
                    childrenRetiredUsage[group.associatedWallet] = retriedUsage
                }
                val wallet = InternalWallet(
                    id = id,
                    category = productCategory!!,
                    ownedBy = owner,
                    localUsage = localUsage,
                    allocationsByParent = allocationByParent,
                    childrenUsage = childrenUsage,
                    localRetiredUsage = localRetiredUsage,
                    childrenRetiredUsage = childrenRetiredUsage,
                    excessUsage = excessUsage,
                    totalAllocated = totalAllocated,
                    totalRetiredAllocated = totalRetiredAllocated,
                    isDirty = false
                )

                walletsById[id] = wallet
            }

            walletsById.values
                .groupBy { it.ownedBy }
                .forEach { (owner, wallets) ->
                    val walletArrayList = ArrayList(wallets)
                    walletsByOwner[owner] = walletArrayList
                }

            // Handle IDs so ID counter is ready to new inserts
            val idRow = session.sendPreparedStatement(
                {},
                """
                    select
                        max(alloc.id) maxAllocation,
                        max(wal.id) maxWallet,
                        max(wo.id) maxOwner,
                        max(ag.id) maxGroup
                    from
                        accounting.wallets_v2 wal
                        , accounting.wallet_allocations_v2 alloc
                        , accounting.wallet_owner wo
                        , accounting.allocation_groups ag
                """
            ).rows.singleOrNull() ?: throw RPCException("Cannot find ids???", HttpStatusCode.InternalServerError)
            val maxAllocationID = idRow.getLong(0)?.toInt()
            val maxWalletID = idRow.getLong(1)?.toInt()
            val maxOwnerID = idRow.getLong(2)?.toInt()
            val maxGroupId = idRow.getLong(3)?.toInt()
            if (maxAllocationID != null) allocationsIdAccumulator.set(maxAllocationID + 1)
            if (maxWalletID != null) walletsIdAccumulator.set(maxWalletID + 1)
            if (maxOwnerID != null) ownersIdAccumulator.set(maxOwnerID + 1)
            if (maxGroupId != null) allocationGroupIdAccumulator.set(maxGroupId + 1)

            if (didChargeOldData) {
                didChargeOldData = false

                session.sendPreparedStatement(
                    {},
                    """
                        delete from accounting.intermediate_usage where true;
                    """
                )
            }
        }

        nextSynchronization = now + 30_000
    }

    override suspend fun loadOldData(system: AccountingSystem) {
        data class Charge(
            val id: Long,
            val walletId: Long,
            val usage: Long
        )

        db.withSession { session ->
            //Charge Intermediate table
            val charges = session.sendPreparedStatement(
                """
                        select id, wallet_id, usage
                        from accounting.intermediate_usage
                    """
            ).rows.map {
                Charge(
                    id = it.getLong(0)!!,
                    walletId = it.getLong(1)!!,
                    usage = it.getLong(2)!!
                )
            }

            charges.map { charge ->
                system.sendRequest(
                    AccountingRequest.SystemCharge(
                        walletId = charge.walletId,
                        amount = charge.usage
                    )
                )
            }

            didChargeOldData = true
        }
    }

    override suspend fun flushChanges() {
        val providerToIdMap = HashMap<Pair<String, String>, Long>()
        db.withSession { session ->
            session.sendPreparedStatement(
                """
                    select id, category, provider from accounting.product_categories
                """
            ).rows.forEach {
                val key = Pair(it.getString(1)!!, it.getString(2)!!)
                val id = it.getLong(0)!!
                providerToIdMap[key] = id
            }

            // Insert or update Owners
            val dirtyOwners = ownersById.filter { it.value.dirty }

            session.sendPreparedStatement(
                {
                    dirtyOwners.entries.split {
                        into("ids") { it.key.toLong() }
                        into("usernames") { (_, o) -> o.reference.takeIf { !o.isProject() }}
                        into("project_ids") { (_, o) -> o.reference.takeIf { o.isProject() }}
                    }
                },
                """
                    with data as (
                        select 
                            unnest(:ids::bigint[]) id,
                            unnest(:usernames::text[]) username,
                            unnest(:project_ids::text[]) project_id
                    )
                    insert into accounting.wallet_owner (id, username, project_id)
                    select id, username, project_id 
                    from data
                    on conflict
                    do nothing;
                """
            )
            dirtyOwners.forEach { id, owner ->
                ownersById[id]!!.dirty = false
            }

            //Insert or update wallets
            val dirtyWallets = walletsById.filter { it.value.isDirty }

            session.sendPreparedStatement(
                {
                    dirtyWallets.entries.split {
                        into("ids") { it.key.toLong() }
                        into("owners") { (id, wallet) -> wallet.ownedBy.toLong() }
                        into("categories") { (id, wallet) -> providerToIdMap[Pair(wallet.category.name, wallet.category.provider)]!! }
                        into("local_usages") { (id, wallet) -> wallet.localUsage }
                        into("local_retired_usages") { (id, wallet) -> wallet.localRetiredUsage }
                        into("excess_usages") { (id, wallet) -> wallet.excessUsage }
                        into("total_amounts_allocated") { (id, wallet) -> wallet.totalAllocated }
                        into("total_retired_amounts") { (id, wallet) -> wallet.totalRetiredAllocated }
                    }
                },
                """
                    with data as (
                        select 
                            unnest(:ids::bigint[]) id,
                            unnest(:owners::bigint[]) owner,
                            unnest(:categories::bigint[]) cateogry,
                            unnest(:local_usages::bigint[]) local_usage,
                            unnest(:local_retired_usages::bigint[]) local_retired_usage,
                            unnest(:excess_usages::bigint[]) excess_usage,
                            unnest(:total_amounts_allocated::bigint[]) total_allocated,
                            unnest(:total_retired_amounts::bigint[]) total_retired_allocated
                    )
                    insert into accounting.wallets_v2 (
                        id,
                        wallet_owner,
                        product_category,
                        local_usage,
                        local_retired_usage,
                        excess_usage,
                        total_allocated,
                        total_retired_allocated
                    )
                    select
                        id,
                        owner,
                        cateogry,
                        local_usage,
                        local_retired_usage,
                        excess_usage,
                        total_allocated,
                        total_retired_allocated
                    from data
                    on conflict (id) 
                    do update 
                    set
                        local_usage = excluded.local_usage,
                        local_retired_usage = excluded.local_retired_usage,
                        excess_usage = excluded.excess_usage,
                        total_allocated = excluded.total_allocated,
                        total_retired_allocated = excluded.total_retired_allocated
                """
            )

            dirtyWallets.forEach { (id, _) ->
                walletsById[id]!!.isDirty = false
            }

            //Insert or update Groups
            val dirtyGroups = allocationGroups.filter { it.value.isDirty }

            session.sendPreparedStatement(
                {
                    dirtyGroups.entries.split {
                        into("ids") { (id, group) -> id.toLong() }
                        into("parent_wallets") { (id, group) -> group.parentWallet.toLong().takeIf { it != 0L } }
                        into("associated_wallets") { (id, group) -> group.associatedWallet.toLong() }
                        into("tree_usages") { (id, group) -> group.treeUsage }
                        into("local_retired_usages") { (id, group) -> group.retiredTreeUsage }
                    }
                },
                """
                    with data as (
                        select 
                            unnest(:ids::bigint[]) id,
                            unnest(:parent_wallets::bigint[]) parent_wallet,
                            unnest(:associated_wallets::bigint[]) associated_wallet,
                            unnest(:tree_usages::bigint[]) tree_usage,
                            unnest(:local_retired_usages::bigint[]) retired_tree_usage
                    )
                    insert into accounting.allocation_groups (
                        id,
                        parent_wallet,
                        associated_wallet,
                        tree_usage,
                        retired_tree_usage
                    )
                    select
                        id,
                        parent_wallet,
                        associated_wallet,
                        tree_usage,
                        retired_tree_usage
                    from data
                    on conflict (id) 
                    do update 
                    set
                        tree_usage = excluded.tree_usage,
                        retired_tree_usage = excluded.retired_tree_usage
                """
            )

            dirtyGroups.forEach { (groupId, _) ->
                allocationGroups[groupId]!!.isDirty = false
            }

            //Insert or update allocations
            val dirtyAllocations = allocations.filter { it.value.isDirty }

            session.sendPreparedStatement(
                {
                    dirtyAllocations.entries.split {
                        into("ids") { (id, alloc) -> id.toLong() }
                        into("associated_allocation_groups") { (id, alloc) -> allocationGroups.values.first { it.allocationSet.contains(id) }.id }
                        into("grants") { (id, alloc) -> alloc.grantedIn }
                        into("quotas") { (id, alloc) -> alloc.quota }
                        into("start_times") { (id, alloc) -> alloc.start }
                        into("end_times") { (id, alloc) -> alloc.end }
                        into("retires") { (id, alloc) -> alloc.retired }
                        into("retired_usages") { (id, alloc) -> alloc.retiredUsage }
                    }
                },
                """
                    with data as (
                        select 
                            unnest(:ids::bigint[]) id,
                            unnest(:associated_allocation_groups::bigint[]) alloc_group,
                            unnest(:grants::bigint[]) granted_in,
                            unnest(:quotas::bigint[]) quota,
                            unnest(:start_times::bigint[]) start_time,
                            unnest(:end_times::bigint[]) end_time,
                            unnest(:retires::bool[]) retired,
                            unnest(:retired_usages::bigint[]) retired_usage
                    )
                    insert into accounting.wallet_allocations_v2(
                        id,
                        associated_allocation_group,
                        granted_in,
                        quota,
                        allocation_start_time,
                        allocation_end_time,
                        retired,
                        retired_usage
                    )
                    select
                        id,
                        alloc_group,
                        granted_in,
                        quota,
                        to_timestamp(start_time / 1000.0),
                        to_timestamp(end_time / 1000.0),
                        retired,
                        retired_usage
                    from data
                    on conflict (id) 
                    do update 
                    set
                        quota = excluded.quota,
                        allocation_start_time = excluded.allocation_start_time,
                        allocation_end_time = excluded.allocation_end_time,
                        retired = excluded.retired,
                        retired_usage = excluded.retired_usage
                """
            )

            dirtyAllocations.forEach { (allocId, _) ->
                allocations[allocId]!!.isDirty = false
            }
        }
    }
}