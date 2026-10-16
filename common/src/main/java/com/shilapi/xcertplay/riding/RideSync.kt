package com.shilapi.xcertplay.riding

import android.content.Context
import androidx.work.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RideApiError(val status: Int, val code: String): Exception(code)
interface RideTransport {
    fun request(path:String,token:String,body:JSONObject?=null):JSONObject
}
class RideApi(private val origin: String = "https://wechat.aiwanwang.cn"): RideTransport {
    override fun request(path: String, token: String, body: JSONObject?): JSONObject {
        val url=URL(origin+path);require(url.protocol=="https")
        val connection=url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout=15000;connection.readTimeout=20000;connection.instanceFollowRedirects=false
            connection.setRequestProperty("Authorization","Bearer $token");connection.setRequestProperty("Content-Type","application/json")
            if(body!=null) { connection.requestMethod="POST";connection.doOutput=true;connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) } }
            val status=connection.responseCode
            val stream=if(status in 200..299) connection.inputStream else connection.errorStream
            val text=stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "{}"
            val result=try { JSONObject(text) } catch(_:Exception) { throw RideApiError(status,"invalid_response") }
            if(status !in 200..299 || !result.optBoolean("ok")) throw RideApiError(status,result.optString("error","server_error"))
            return result
        } finally { connection.disconnect() }
    }
}
object RideRuntime {
    val io = Executors.newSingleThreadExecutor()
    val network = Executors.newSingleThreadExecutor()
    @Volatile private var initialized=false
    @Synchronized fun initialize(context:Context) {
        if(initialized) return
        initialized=true
        val app=context.applicationContext
        runCatching {
            app.getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(object:android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network:android.net.Network) { schedule(app) }
            })
        }.onFailure { android.util.Log.w("DiPlay-Riding","Network listener unavailable; periodic sync remains scheduled",it) }
        schedule(app)
    }
    private val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun schedule(context: Context, manualRetry: Boolean = false) {
        runCatching {
            val manager=WorkManager.getInstance(context)
            manager.enqueueUniqueWork("riding-sync",if(manualRetry) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<RideSyncWorker>().setInputData(workDataOf("manual_retry" to manualRetry)).setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
            manager.enqueueUniquePeriodicWork("riding-sync-fallback",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<RideSyncWorker>(15,TimeUnit.MINUTES).setConstraints(constraints).build())
        }.onFailure { android.util.Log.w("DiPlay-Riding","Sync scheduling unavailable; local rides retained",it) }
    }
    fun binding(context: Context): RideBinding = RideBinding.get(context.applicationContext)
}

/** Runs independently of Activity and CarPlay. Each ACK is durable before the next request. */
class RideSyncWorker(context: Context, params: WorkerParameters): Worker(context,params) {
    override fun doWork(): Result = synchronized(SYNC_LOCK) { sync() }
    companion object { private val SYNC_LOCK=Any() }
    private fun sync(): Result {
        val binding=RideRuntime.binding(applicationContext);val store=RideStore.get(applicationContext)
        return try {
            val snapshot=binding.read()
            if(snapshot.optString("binding_state")!="BOUND") return Result.success()
            val id=snapshot.getString("device_id"); val credential=snapshot.getString("device_credential"); val api=RideApi()
            val verified=api.request("/api/v1/riding/devices/$id/verify",credential,JSONObject())
            if(verified.optString("binding_state")!="BOUND" || verified.getInt("binding_version")!=snapshot.getInt("binding_version")) throw RideApiError(403,"device_revoked")
            // Merge with freshly read state to avoid overwriting a concurrent local binding change.
            synchronized(RideBinding::class.java) {
                val fresh=binding.read()
                if(fresh.optString("device_id")==id) { fresh.put("last_verified_at",verified.getLong("last_verified_at"));binding.save(fresh) }
            }
            val complete=RideSyncQueue(store,api).uploadPending(id,snapshot.getInt("binding_version"),credential,
                manualRetry=inputData.getBoolean("manual_retry",false),stopped={isStopped}) { ride ->
                if(!ride.active) synchronized(RideBinding::class.java) {
                    val fresh=binding.read()
                    if(fresh.optString("device_id")==id) {fresh.put("last_sync_at",System.currentTimeMillis());binding.save(fresh)}
                }
            }
            if(complete) Result.success() else Result.retry()
        } catch(error:RideApiError) {
            if(error.code=="device_revoked" || error.status==401) {
                synchronized(RideBinding::class.java) { val value=binding.read();value.put("binding_state","REVOKED");binding.save(value);store.quarantine(value.optString("device_id")) }
                Result.success()
            } else Result.retry()
        } catch(_:Exception) { Result.retry() }
    }
}

/** One rejected ride must not prevent the next saved ride from synchronizing. */
class RideSyncQueue(private val store:RideStore,private val api:RideTransport) {
    fun uploadPending(deviceId:String,version:Int,credential:String,manualRetry:Boolean=false,
                      stopped:()->Boolean={false},uploaded:(RideSession)->Unit={}):Boolean {
        if(manualRetry) store.retryConflicts(deviceId,version)
        for(ride in store.pending()) {
            if(stopped()) return false
            if(ride.deviceId!=deviceId || ride.version!=version) {store.quarantine(ride.deviceId);continue}
            try {RideSyncEngine(store,api).upload(ride,credential,stopped)}
            catch(error:RideApiError) {
                if(error.code=="device_revoked" || error.status==401) throw error
                val permanent=error.status in listOf(400,404,409,410,422)
                store.syncError(ride.id,error.code,permanent)
                if(permanent) continue else return false
            } catch(_:Exception) {store.syncError(ride.id,"network_error");return false}
            uploaded(ride)
        }
        return true
    }
}

/** Small protocol engine shared by the real worker and deterministic failure/retry tests. */
class RideSyncEngine(private val store:RideStore,private val api:RideTransport) {
    fun upload(ride:RideSession,credential:String,stopped:()->Boolean={false}) {
        val upsert=api.request("/api/v1/riding/sessions/upsert",credential,JSONObject().put("local_session_id",ride.id).put("started_at",ride.started).put("direction",ride.direction).put("coordinate_type","wgs84").put("binding_version",ride.version))
        var ack=upsert.getLong("highest_contiguous_seq")
        require(ack in 0..ride.count);store.acknowledge(ride.id,ack)
        while(ack<ride.count && !stopped()) {
            val points=store.batch(ride.id,ack,minOf(250L,ride.count-ack).toInt())
            check(points.length()>0) {"missing_local_points"}
            store.syncing(ride.id)
            val response=api.request("/api/v1/riding/sessions/${ride.id}/points/batch",credential,JSONObject().put("from_seq",ack+1).put("points",points))
            val next=response.getLong("highest_contiguous_seq");require(next>ack && next<=ride.count)
            store.acknowledge(ride.id,next);ack=next
        }
        if(stopped()) throw java.io.IOException("sync_stopped")
        if(!ride.active && ride.finished!=null) {
            store.syncing(ride.id)
            api.request("/api/v1/riding/sessions/${ride.id}/finish",credential,JSONObject().put("finished_at",ride.finished).put("point_count",ride.count).put("pauses",store.pauses(ride.id)))
            store.synced(ride.id)
        }
    }
}
