package dk.sdu.cloud.accounting.services.accounting

// c4367170c8952028827b7ce164a90e7cf42741f5

import kotlin.math.min

class Graph(
    val vertexCount: Int,
    val adjacent: Array<LongArray>,
    val cost: Array<LongArray>,
    val original: Array<BooleanArray>,
    var index: List<Int>,
    var indexInv: HashMap<Int, Int>,
) {
    fun addEdge(source: Int, destination: Int, capacity: Long, flow: Long) {
        adjacent[source][destination] = capacity
        adjacent[destination][source] = flow

        val midway = vertexCount / 2
        if (destination < midway && source < midway) {
            original[source][destination] = true
        }
    }

    fun addEdgeCost(source: Int, destination: Int, edgeCost: Long) {
        cost[source][destination] = edgeCost
        cost[destination][source] = -edgeCost
    }

    /**
     * Finds the BFS-ordered path from [source] to [destination]
     *
     * [parent] is the path found expressed as `parent.get(childId) = parentId`
     *
     * @return false if a path does not exist, otherwise true
     */
    private fun bfs(source: Int, destination: Int, parent: IntArray): Boolean {
        val visited = BooleanArray(vertexCount)
        val queue = ArrayDeque<Int>(vertexCount)
        queue.add(source)
        visited[source] = true

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            for ((index, value) in adjacent[node].withIndex()) {
                if (!visited[index] && value > 0) {
                    queue.add(index)
                    visited[index] = true
                    parent[index] = node
                }
            }
        }

        return visited[destination]
    }

    /**
     * Finds the maximum flow from source to destination using the Edmonds-Karp version of the Ford-Fulkerson method.
     *
     * NOTE: The graph is modified by this function. After the function call, the graph represents the residual graph.
     */
    fun maxFlow(source: Int, destination: Int): Long {
        var maxFlow = 0L
        val path = IntArray(vertexCount)
        while (bfs(source, destination, path)) {
            val pathFlow = flow(source, destination, path)
            addFlow(source, destination, path, pathFlow)
            maxFlow += pathFlow
        }
        return maxFlow
    }

    /**
     * Finds the flow capacity along a given path
     */
    private fun flow(source: Int, destination: Int, path: IntArray): Long {
        var flow = Long.MAX_VALUE
        var currentNode = destination
        while (currentNode != source) {
            val nextNode = path[currentNode]
            flow = min(flow, adjacent[nextNode][currentNode])
            currentNode = nextNode
        }

        return flow
    }

    private fun addFlow(source: Int, destination: Int, path: IntArray, flow: Long) {
        var currentNode = destination
        while (currentNode != source) {
            val nextNode = path[currentNode]
            adjacent[nextNode][currentNode] -= flow
            adjacent[currentNode][nextNode] += flow
            currentNode = nextNode
        }
    }

    private fun notVisited(node: Int, path: List<Int>) = path.none { it == node }

    private fun leastExpensivePath(source: Int, destination: Int): Pair<Long, IntArray> {
        val parent = IntArray(vertexCount)
        var minCost = Long.MAX_VALUE
        val queue = ArrayDeque<List<Int>>(8)
        queue.add(listOf(source))

        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            if (path.last() == destination) {
                val pathCost = (0 until path.size - 1)
                    .map { i -> cost[path[i]][path[i + 1]] }
                    .let { it.sum() / it.size }

                if (pathCost < minCost) {
                    minCost = pathCost
                    for (i in (path.size - 1) downTo 1) {
                        parent[path[i]] = path[i - 1]
                    }
                }
                continue
            }

            for ((index, value) in adjacent[path.last()].withIndex()) {
                if (value > 0 && notVisited(index, path)) {
                    val newPath = path + index
                    queue.add(newPath)
                }
            }
        }

        return Pair(minCost, parent)
    }

    fun minCostFlow(source: Int, destination: Int, desiredFlow: Long): Long {
        var actualFlow = 0L
        while (actualFlow < desiredFlow) {
            val (pathCost, path) = leastExpensivePath(source, destination)
            if (pathCost == Long.MAX_VALUE) break

            var flowToApply = flow(source, destination, path)
            flowToApply = min(flowToApply, desiredFlow - actualFlow)
            actualFlow += flowToApply
            addFlow(source, destination, path, flowToApply)
        }
        return actualFlow
    }

    companion object {
        fun create(vertexCount: Int): Graph {
            val adjacent = Array(vertexCount) { LongArray(vertexCount) }
            val cost = Array(vertexCount) { LongArray(vertexCount) }
            val original = Array(vertexCount) { BooleanArray(vertexCount) }
            return Graph(vertexCount, adjacent, cost, original, emptyList(), HashMap())
        }
    }
}
