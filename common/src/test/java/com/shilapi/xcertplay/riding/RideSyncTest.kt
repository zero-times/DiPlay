package com.shilapi.xcertplay.riding

import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RideSyncTest {
    private lateinit var store:RideStore
    @Before fun setup() {val context=RuntimeEnvironment.getApplication();context.deleteDatabase(File(context.noBackupFilesDir,"riding-v1.sqlite").absolutePath);store=RideStore(context)}
    @After fun cleanup() {store.close()}
    @Test fun lostBatchResponseAndFinishResponseRetryFromServerAckWithoutDuplicatingData() {
        val id=store.start("device",2,"return",1000)
        for(i in 2..602) store.append(RidePoint(i*1000L,22+i*.000001,113.0,5.0,2.0,provider="gps"))
        store.pause(603000);store.resume(604000);store.finish(605000)
        val points=mutableMapOf<Long,String>();var ack=0L;var failBatch=true;var failFinish=true;var completed=false
        val api=object:RideTransport {
            override fun request(path:String,token:String,body:JSONObject?):JSONObject {
                assertEquals("device-secret",token)
                when {
                    path.endsWith("upsert") -> {assertEquals("return",body!!.getString("direction"));assertEquals("wgs84",body.getString("coordinate_type"));assertEquals(2,body.getInt("binding_version"))}
                    path.endsWith("batch") -> {
                        val batch=body!!.getJSONArray("points");assertTrue(batch.length()<=250)
                        for(i in 0 until batch.length()) {val p=batch.getJSONObject(i);val seq=p.getLong("seq");points[seq]?.let {assertEquals(it,p.toString())};points[seq]=p.toString()}
                        while(points.containsKey(ack+1))ack++
                        if(failBatch) {failBatch=false;throw IOException("lost_response")}
                    }
                    path.endsWith("finish") -> {assertEquals(601,body!!.getInt("point_count"));assertEquals(1,body.getJSONArray("pauses").length());assertEquals(601L,ack);completed=true;if(failFinish){failFinish=false;throw IOException("lost_finish_response")}}
                }
                return JSONObject().put("highest_contiguous_seq",ack).put("ride_id",if(completed)"same-ride-id" else JSONObject.NULL)
            }
        }
        val engine=RideSyncEngine(store,api)
        repeat(2) {try {engine.upload(store.latest()!!,"device-secret");fail("simulated transport failure expected")}catch(_:IOException) {}}
        assertEquals(601,points.size);assertNotEquals("SYNCED",store.latest()!!.syncState)
        store.close()
        val context=RuntimeEnvironment.getApplication();store=RideStore(context)
        RideSyncEngine(store,api).upload(store.latest()!!,"device-secret")
        assertEquals("SYNCED",store.latest()!!.syncState);assertEquals(601L,store.latest()!!.ack)
    }

    @Test fun zeroPointConflictsRetryOnlyOnRequestAndKeepTheSameSession() {
        val id=store.start("device",2,"outbound",1000);store.finish(5000)
        store.syncError(id,"invalid_finish",true)
        val other=store.start("another-device",1,"return",6000);store.finish(7000);store.syncError(other,"invalid_finish",true)
        val revoked=store.start("revoked-device",1,"return",8000);store.finish(9000);store.quarantine("revoked-device")
        var requests=0
        val api=object:RideTransport {
            override fun request(path:String,token:String,body:JSONObject?):JSONObject {
                requests++
                if(path.endsWith("upsert")) assertEquals(id,body!!.getString("local_session_id"))
                if(path.endsWith("finish")) assertEquals(0,body!!.getInt("point_count"))
                return JSONObject().put("highest_contiguous_seq",0)
            }
        }
        val queue=RideSyncQueue(store,api)
        assertTrue(queue.uploadPending("device",2,"credential"));assertEquals(0,requests)
        assertTrue(queue.uploadPending("device",2,"credential",manualRetry=true))
        assertEquals(2,requests)
        assertEquals("SYNCED",store.history().single {it.id==id}.syncState)
        assertEquals("CONFLICT",store.history().single {it.id==other}.syncState)
        assertEquals("QUARANTINED",store.history().single {it.id==revoked}.syncState)
        assertEquals(0L,store.history().single {it.id==id}.count)
    }
    @Test fun oneRejectedRideDoesNotBlockTheNextAndManualRetryPreservesAcknowledgedPoints() {
        val rejected=store.start("device",2,"outbound",1000);store.finish(5000)
        val good=store.start("device",2,"return",6000)
        store.append(RidePoint(7000,22.0,113.0,4.0,2.0,provider="gps"));store.finish(8000)
        store.acknowledge(good,1)
        val uploads=mutableListOf<String>()
        val api=object:RideTransport {
            override fun request(path:String,token:String,body:JSONObject?):JSONObject {
                if(path.endsWith("upsert")) {
                    val id=body!!.getString("local_session_id");uploads.add(id)
                    if(id==rejected) throw RideApiError(409,"ride_overlap")
                }
                return JSONObject().put("highest_contiguous_seq",1)
            }
        }
        assertTrue(RideSyncQueue(store,api).uploadPending("device",2,"credential"))
        assertEquals(listOf(rejected,good),uploads)
        assertEquals("CONFLICT",store.history().single {it.id==rejected}.syncState)
        assertEquals("SYNCED",store.history().single {it.id==good}.syncState)
        store.retryConflicts("device",2)
        assertEquals(1L,store.history().single {it.id==good}.ack)
        assertEquals("SYNCED",store.history().single {it.id==good}.syncState)
    }
}
