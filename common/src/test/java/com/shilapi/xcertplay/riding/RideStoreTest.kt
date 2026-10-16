package com.shilapi.xcertplay.riding

import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RideStoreTest {
    private lateinit var context:Context
    private lateinit var store:RideStore
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        context.deleteDatabase(File(context.noBackupFilesDir,"riding-v1.sqlite").absolutePath)
        store=RideStore(context)
    }
    @After fun cleanup() {store.close()}
    private fun fix(n:Int)=RidePoint(n*1000L,22.0+n*.000001,113.0,4.0,2.0,provider="gps")
    @Test fun persistedRideSurvivesReopenPauseAndContiguousAcknowledgements() {
        val id=store.start("device-a",1,"outbound",1000)
        assertEquals(id,store.start("device-a",1,"return",1000))
        assertTrue(store.append(fix(2)));assertFalse(store.append(fix(2)))
        assertTrue(store.append(fix(3)))
        store.pause(4000);assertFalse(store.append(fix(5)))
        store.close();store=RideStore(context)
        assertEquals("PAUSED",store.active()!!.state);assertEquals(2L,store.active()!!.count)
        store.resume(8000);assertTrue(store.append(fix(9)))
        store.finish(10000)
        val ride=store.latest()!!
        assertEquals(5000L,ride.movingMs(20000));assertEquals(3L,ride.count)
        assertTrue(ride.distance<1);assertEquals(1L,store.batch(id,0).getJSONObject(0).getLong("seq"))
        store.acknowledge(id,2);store.acknowledge(id,1);assertEquals(2L,store.latest()!!.ack)
        store.acknowledge(id,4);assertEquals(2L,store.latest()!!.ack)
        store.synced(id);assertNotEquals("SYNCED",store.latest()!!.syncState)
        store.acknowledge(id,3);store.synced(id);assertEquals("SYNCED",store.latest()!!.syncState)
    }
    @Test fun twoHoursOfOfflinePointsAreDurableAndNeverDeletedByQuarantine() {
        val id=store.start("old-device",7,"return",1000)
        for(n in 2..7201) assertTrue(store.append(fix(n)))
        store.finish(7202000);store.close();store=RideStore(context)
        assertEquals(7200L,store.latest()!!.count)
        var read=0L
        while(read<7200) {val points=store.batch(id,read);assertEquals(read+1,points.getJSONObject(0).getLong("seq"));read+=points.length()}
        assertEquals(7200L,read)
        store.quarantine("old-device");assertEquals("QUARANTINED",store.latest()!!.syncState);assertTrue(store.pending().isEmpty())
        assertEquals(250,store.batch(id,0).length())
        store.start("new-device",1,"outbound",8000000)
        assertEquals(2,store.history().size);assertEquals("old-device",store.history()[1].deviceId)
    }
    @Test fun badAccuracyJumpsAndPauseDoNotCreateDistance() {
        store.start("device",1,"outbound",1000)
        store.append(fix(2));store.append(fix(3).copy(accuracy=100.0));store.append(fix(4))
        assertEquals(0.0,store.latest()!!.distance,0.01)
        store.append(fix(5).copy(lat=50.0));store.append(fix(6))
        assertEquals(0.0,store.latest()!!.distance,0.01)
        store.pause(7000);store.quarantine("device");store.finish(8000)
        assertEquals("QUARANTINED",store.latest()!!.syncState)
    }
}
