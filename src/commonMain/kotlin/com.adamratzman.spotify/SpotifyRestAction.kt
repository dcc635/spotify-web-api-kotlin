/* Spotify Web API, Kotlin Wrapper; MIT License, 2017-2020; Original author: Adam Ratzman */
package com.adamratzman.spotify

import com.adamratzman.spotify.annotations.SpotifyExperimentalHttpApi
import com.adamratzman.spotify.models.PagingObjectBase
import com.adamratzman.spotify.utils.TimeUnit
import com.adamratzman.spotify.utils.getCurrentTimeMs
import com.adamratzman.spotify.utils.runBlockingMpp
import com.adamratzman.spotify.utils.schedule
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.jvm.JvmOverloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Provides a uniform interface to retrieve, whether synchronously or asynchronously, [T] from Spotify
 */
open class SpotifyRestAction<T> internal constructor(protected val api: GenericSpotifyApi, val supplier: suspend () -> T) {
    private var hasRunBacking: Boolean = false
    private var hasCompletedBacking: Boolean = false

    lateinit var scope: CoroutineContext

    /**
     * Whether this REST action has been *commenced*.
     *
     * Not to be confused with [hasCompleted]
     */
    fun hasRun() = hasRunBacking

    /**
     * Whether this REST action has been fully *completed*
     */
    fun hasCompleted() = hasCompletedBacking

    /**
     * Invoke [supplier] and synchronously retrieve [T]
     */
    fun complete(): T = runBlockingMpp {
        suspendComplete()
    }

    /**
     * Suspend the coroutine, invoke [SpotifyRestAction.supplier] asynchronously/queued and resume with result [T]
     * */
    suspend fun suspendQueue(): T = suspendCoroutine { continuation ->
        queue({ throwable ->
            continuation.resumeWithException(throwable)
        }) { result ->
            continuation.resume(result)
        }
    }

    /**
     * Switch to given [context][context], invoke [SpotifyRestAction.supplier] and synchronously retrieve [T]
     *
     * @param context The context to execute the [SpotifyRestAction.complete] in
     * */
    @Suppress("UNCHECKED_CAST")
    @JvmOverloads
    suspend fun suspendComplete(context: CoroutineContext = Dispatchers.Default): T = withContext(context) {
        hasRunBacking = true
        return@withContext try {
            supplier().also { hasCompletedBacking = true }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw e
        }
    }

    /**
     * Invoke [supplier] asynchronously and consume [consumer] with the [T] value returned
     *
     * @param failure Consumer to invoke when an exception is thrown by [supplier]
     * @param consumer to be invoked with [T] after successful completion of [supplier]
     */
    @JvmOverloads
    fun queue(failure: (suspend (Throwable) -> Unit) = { throw it }, consumer: (suspend (T) -> Unit) = {}) {
        hasRunBacking = true
        runBlockingMpp {
            coroutineScope {
                launch {
                    try {
                        val result = suspendComplete()
                        consumer(result)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        failure(t)
                    }
                }
            }
        }
    }

    /**
     * Invoke [supplier] asynchronously immediately and invoke [consumer] after the specified quantity of time
     *
     * @param quantity amount of time
     * @param timeUnit the unit that [quantity] is in
     * @param consumer to be invoked with [T] after successful completion of [supplier]
     */
    @JvmOverloads
    fun queueAfter(
        quantity: Int,
        timeUnit: TimeUnit = TimeUnit.SECONDS,
        scope: CoroutineScope? = null,
        failure: (Throwable) -> Unit = { throw it },
        consumer: (T) -> Unit
    ) {
        val runAt = getCurrentTimeMs() + timeUnit.toMillis(quantity.toLong())
        queue({ exception ->
            (scope ?: coroutineScope { this }).schedule((runAt - getCurrentTimeMs()).toInt(), TimeUnit.MILLISECONDS) { failure(exception) }
        }) { result ->
            (scope ?: coroutineScope { this }).schedule((runAt - getCurrentTimeMs()).toInt(), TimeUnit.MILLISECONDS) { consumer(result) }
        }
    }

    override fun toString() = complete().toString()
}

class SpotifyRestActionPaging<Z : Any, T : PagingObjectBase<Z>>(api: GenericSpotifyApi, supplier: suspend () -> T) :
        SpotifyRestAction<T>(api, supplier) {

    /**
     * Synchronously retrieve all [PagingObjectBase] associated with this rest action
     */
    fun getAll(context: CoroutineContext = Dispatchers.Default) = api.tracks.toAction { suspendComplete(context).getAllImpl() }

    /**
     * Synchronously retrieve the next [total] paging objects associated with this rest action, including the current one.
     *
     * @param total The total amount of [PagingObjectBase] to request, including the [PagingObjectBase] associated with the current request.
     * @since 3.0.0
     */
    @SpotifyExperimentalHttpApi
    fun getWithNext(total: Int, context: CoroutineContext = Dispatchers.Default) = api.tracks.toAction { suspendComplete(context).getWithNextImpl(total) }

    /**
     * Synchronously retrieve the items associated with the next [total] paging objects associated with this rest action, including the current one.
     *
     * @param total The total amount of [PagingObjectBase] to request, including the [PagingObjectBase] associated with the current request.
     * @since 3.0.0
     */
    @SpotifyExperimentalHttpApi
    fun getWithNextItems(total: Int, context: CoroutineContext = Dispatchers.Default) = api.tracks.toAction { getWithNext(total, context).complete().map { it.items }.flatten() }

    /**
     * Synchronously retrieve all [Z] associated with this rest action. Filters out null objects
     *
     * @since 3.1.0
     */
    fun getAllItemsNotNull(context: CoroutineContext = Dispatchers.Default) =
            api.tracks.toAction {
                suspendComplete(context)
                        .getAllImpl().toList().map { it.items }.flatten().filterNotNull()
            }

    /**
     * Synchronously retrieve all [Z] associated with this rest action
     */
    fun getAllItems(context: CoroutineContext = Dispatchers.Default) =
            api.tracks.toAction {
                suspendComplete(context)
                        .getAllImpl().toList().map { it.items }.flatten()
            }

    /**
     * Consume each [Z] by [consumer] as it is retrieved
     */
    fun streamAllItems(context: CoroutineContext = Dispatchers.Default, consumer: (Z?) -> Unit): SpotifyRestAction<Unit> {
        return api.tracks.toAction {
            suspendComplete(context).getAllImpl().toList().forEach { it.items.forEach { item -> consumer(item) } }
        }
    }

    /**
     * Flow the paging action ordered. This can be less performant than [flow] if you are in the middle of the pages.
     * */
    @FlowPreview
    @JvmOverloads
    @ExperimentalCoroutinesApi
    fun flowOrdered(context: CoroutineContext = Dispatchers.Default) = flow {
        emitAll(flowPagingObjectsOrdered().flatMapConcat { it.asFlow() })
    }.flowOn(context)

    /**
     * Flow the paging objects ordered. This can be less performant than [flowPagingObjects] if you are in the middle of the pages.
     * */
    @JvmOverloads
    @ExperimentalCoroutinesApi
    fun flowPagingObjectsOrdered(context: CoroutineContext = Dispatchers.Default) =
            flow {
                suspendComplete(context).also { master ->
                    emitAll(master.flowStartOrdered())
                    emit(master)
                    emitAll(master.flowEndOrdered())
                }
            }.flowOn(context)

    /**
     * Flow the Paging action.
     * */
    @FlowPreview
    @JvmOverloads
    @ExperimentalCoroutinesApi
    fun flow(context: CoroutineContext = Dispatchers.Default) = flow {
        emitAll(flowPagingObjects().flatMapConcat { it.asFlow() })
    }.flowOn(context)

    /**
     * Flow the paging objects.
     * */
    @JvmOverloads
    @ExperimentalCoroutinesApi
    fun flowPagingObjects(context: CoroutineContext = Dispatchers.Default) =
            flow {
                suspendComplete(context).also { master ->
                    emitAll(master.flowBackward())
                    emit(master)
                    emitAll(master.flowForward())
                }
            }.flowOn(context)
}
