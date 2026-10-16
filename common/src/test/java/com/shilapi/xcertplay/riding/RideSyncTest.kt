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
}
