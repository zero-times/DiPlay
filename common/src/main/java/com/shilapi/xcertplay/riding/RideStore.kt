package com.shilapi.xcertplay.riding

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.*

data class RidePoint(val timestamp: Long, val lat: Double, val lng: Double, val accuracy: Double?,
    val speed: Double?, val altitude: Double? = null, val bearing: Double? = null, val provider: String? = null,
    val quality: String = "GOOD") {
    fun json(seq: Long) = JSONObject().put("seq", seq).put("timestamp", timestamp).put("lat", lat).put("lng", lng)
        .put("accuracy_m", accuracy ?: JSONObject.NULL).put("speed_mps", speed ?: JSONObject.NULL)
        .put("altitude_m", altitude ?: JSONObject.NULL).put("bearing_deg", bearing ?: JSONObject.NULL)
        .put("provider", provider ?: JSONObject.NULL).put("quality", quality)
}
data class RideSession(val id: String, val deviceId: String, val version: Int, val direction: String,
    val started: Long, val finished: Long?, val state: String, val distance: Double, val pausedMs: Long,
    val pauseStart: Long?, val count: Long, val ack: Long, val maxSpeed: Double, val speed: Double,
    val syncState: String, val error: String) {
    fun movingMs(now: Long) = ((finished ?: now) - started - pausedMs - (pauseStart?.let { (finished ?: now) - it } ?: 0)).coerceAtLeast(0)
    fun average(now: Long) = if (movingMs(now) > 0) distance / (movingMs(now) / 1000.0) else 0.0
    val active get() = state == "RECORDING" || state == "PAUSED"
}

/** Raw points and aggregate changes commit together, before callers publish a UI update or enqueue sync. */
class RideStore(context: Context) : SQLiteOpenHelper(context,
    File(context.noBackupFilesDir, "riding-v1.sqlite").absolutePath, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE rides (id TEXT PRIMARY KEY, device_id TEXT NOT NULL, version INTEGER NOT NULL,
            direction TEXT NOT NULL, started INTEGER NOT NULL, finished INTEGER, state TEXT NOT NULL,
            distance REAL NOT NULL DEFAULT 0, paused_ms INTEGER NOT NULL DEFAULT 0, pause_start INTEGER,
            count INTEGER NOT NULL DEFAULT 0, ack INTEGER NOT NULL DEFAULT 0, max_speed REAL NOT NULL DEFAULT 0,
            speed REAL NOT NULL DEFAULT 0, sync_state TEXT NOT NULL DEFAULT 'PENDING', error TEXT NOT NULL DEFAULT '')""")
        db.execSQL("CREATE TABLE points (ride_id TEXT NOT NULL, seq INTEGER NOT NULL, timestamp INTEGER NOT NULL, data TEXT NOT NULL, PRIMARY KEY(ride_id,seq))")
        db.execSQL("CREATE TABLE pauses (ride_id TEXT NOT NULL, started INTEGER NOT NULL, ended INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX one_active_ride ON rides((1)) WHERE state IN ('RECORDING','PAUSED')")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    private fun read(c: Cursor) = RideSession(c.getString(0), c.getString(1), c.getInt(2), c.getString(3), c.getLong(4),
        if (c.isNull(5)) null else c.getLong(5), c.getString(6), c.getDouble(7), c.getLong(8),
        if (c.isNull(9)) null else c.getLong(9), c.getLong(10), c.getLong(11), c.getDouble(12), c.getDouble(13), c.getString(14), c.getString(15))
    @Synchronized fun latest(): RideSession? = readableDatabase.rawQuery("SELECT * FROM rides ORDER BY started DESC LIMIT 1", null).use { if (it.moveToFirst()) read(it) else null }
    @Synchronized fun active(): RideSession? = readableDatabase.rawQuery("SELECT * FROM rides WHERE state IN ('RECORDING','PAUSED') LIMIT 1", null).use { if (it.moveToFirst()) read(it) else null }
    @Synchronized fun history(): List<RideSession> = readableDatabase.rawQuery("SELECT * FROM rides ORDER BY started DESC LIMIT 50", null).use { c -> buildList { while (c.moveToNext()) add(read(c)) } }
    @Synchronized fun pending(): List<RideSession> = readableDatabase.rawQuery("SELECT * FROM rides WHERE sync_state NOT IN ('SYNCED','QUARANTINED','CONFLICT') ORDER BY started", null).use { c -> buildList { while (c.moveToNext()) add(read(c)) } }
    @Synchronized fun start(deviceId: String, version: Int, direction: String, now: Long): String {
        require(direction in listOf("outbound", "return"))
        active()?.let { return it.id }
        val id = UUID.randomUUID().toString()
        writableDatabase.insertOrThrow("rides", null, ContentValues().apply {
            put("id", id); put("device_id", deviceId); put("version", version); put("direction", direction); put("started", now); put("state", "RECORDING")
        })
        return id
    }
    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase; db.beginTransaction()
        try { val result = block(db); db.setTransactionSuccessful(); return result } finally { db.endTransaction() }
    }
    @Synchronized fun pause(now: Long) = active()?.takeIf { it.state == "RECORDING" }?.let {
        writableDatabase.execSQL("UPDATE rides SET state='PAUSED',pause_start=?,speed=0 WHERE id=?", arrayOf(now.coerceAtLeast(it.started), it.id))
    }
    private fun closePause(db: SQLiteDatabase, ride: RideSession, now: Long) {
        ride.pauseStart?.let {
            db.execSQL("INSERT INTO pauses VALUES (?,?,?)", arrayOf(ride.id, it, now))
            db.execSQL("UPDATE rides SET paused_ms=paused_ms+?,pause_start=NULL WHERE id=?", arrayOf(now - it, ride.id))
        }
    }
    @Synchronized fun resume(now: Long) = transaction { db -> active()?.takeIf { it.state == "PAUSED" }?.let {
        closePause(db, it, now.coerceAtLeast(it.pauseStart ?: it.started)); db.execSQL("UPDATE rides SET state='RECORDING' WHERE id=?", arrayOf(it.id))
    } }
    @Synchronized fun finish(now: Long) = transaction { db -> active()?.let {
        val end = now.coerceAtLeast(it.pauseStart ?: it.started)
        closePause(db, it, end)
        db.execSQL("UPDATE rides SET state='FINISHED_LOCAL',finished=?,speed=0,sync_state=CASE WHEN sync_state='QUARANTINED' THEN sync_state ELSE 'PENDING' END WHERE id=?", arrayOf(end, it.id))
    } }
    @Synchronized fun append(input: RidePoint): Boolean = transaction { db ->
        val ride = active()?.takeIf { it.state == "RECORDING" } ?: return@transaction false
        if (input.timestamp < ride.started || input.timestamp - ride.started > 48 * 3600000L || ride.count >= 86400) return@transaction false
        val last = db.rawQuery("SELECT data FROM points WHERE ride_id=? ORDER BY seq DESC LIMIT 1", arrayOf(ride.id)).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }
        if (last != null && input.timestamp <= last.getLong("timestamp")) return@transaction false
        val low = input.accuracy == null || input.accuracy > 50
        val seconds = last?.let { (input.timestamp - it.getLong("timestamp")) / 1000.0 } ?: 0.0
        val meters = last?.let { meters(it.getDouble("lat"), it.getDouble("lng"), input.lat, input.lng) } ?: 0.0
        val outlier = seconds > 0 && meters / seconds > 100 / 3.6
        val quality = if (low) "LOW_ACCURACY" else if (outlier) "OUTLIER" else "GOOD"
        val acrossPause = last != null && db.rawQuery("SELECT 1 FROM pauses WHERE ride_id=? AND started>=? AND started<=? LIMIT 1", arrayOf(ride.id, last.getLong("timestamp").toString(), input.timestamp.toString())).use { it.moveToFirst() }
        val goodSegment = quality == "GOOD" && last?.optString("quality") == "GOOD" && seconds in 0.001..90.0 && !acrossPause
        val rawSpeed = if (quality == "GOOD") input.speed?.takeIf { it in 0.0..(100/3.6) } ?: if (goodSegment) meters / seconds else 0.0 else 0.0
        // Last four persisted good samples plus this fix: no UI-only buffer is needed after process death.
        val speeds = mutableListOf(rawSpeed)
        db.rawQuery("SELECT data FROM points WHERE ride_id=? ORDER BY seq DESC LIMIT 4", arrayOf(ride.id)).use { c ->
            while (c.moveToNext()) { val p = JSONObject(c.getString(0)); if (p.optString("quality") == "GOOD" && !p.isNull("speed_mps") && input.timestamp-p.getLong("timestamp") <= 5000) speeds.add(p.getDouble("speed_mps").coerceIn(0.0,100/3.6)) }
        }
        val speed = if (quality == "GOOD") speeds.average() else 0.0
        db.execSQL("INSERT INTO points VALUES (?,?,?,?)", arrayOf(ride.id, ride.count+1, input.timestamp, input.copy(quality=quality).json(ride.count+1).toString()))
        db.execSQL("UPDATE rides SET count=count+1,distance=distance+?,speed=?,max_speed=MAX(max_speed,?) WHERE id=?", arrayOf(if(goodSegment) meters else 0.0,speed,rawSpeed,ride.id))
        true
    }
    @Synchronized fun lastPoint(id:String):JSONObject? = readableDatabase.rawQuery("SELECT data FROM points WHERE ride_id=? ORDER BY seq DESC LIMIT 1",arrayOf(id)).use {if(it.moveToFirst()) JSONObject(it.getString(0)) else null}
    @Synchronized fun batch(id: String, after: Long, limit: Int = 250): JSONArray = readableDatabase.rawQuery(
        "SELECT data FROM points WHERE ride_id=? AND seq>? ORDER BY seq LIMIT ?", arrayOf(id,after.toString(),limit.toString())).use { c -> JSONArray().apply { while (c.moveToNext()) put(JSONObject(c.getString(0))) } }
    @Synchronized fun pauses(id: String): JSONArray = readableDatabase.rawQuery("SELECT started,ended FROM pauses WHERE ride_id=? ORDER BY started", arrayOf(id)).use { c -> JSONArray().apply { while(c.moveToNext()) put(JSONObject().put("started_at",c.getLong(0)).put("ended_at",c.getLong(1))) } }
    @Synchronized fun acknowledge(id: String, seq: Long) {
        writableDatabase.execSQL("UPDATE rides SET ack=MAX(ack,?),sync_state='PENDING',error='' WHERE id=? AND ?>=0 AND ?<=count",arrayOf(seq,id,seq,seq))
    }
    @Synchronized fun syncing(id:String) {writableDatabase.execSQL("UPDATE rides SET sync_state='SYNCING' WHERE id=? AND sync_state!='QUARANTINED'",arrayOf(id))}
    @Synchronized fun synced(id: String) { writableDatabase.execSQL("UPDATE rides SET sync_state='SYNCED',error='' WHERE id=? AND state='FINISHED_LOCAL' AND ack=count",arrayOf(id)) }
    @Synchronized fun syncError(id: String, code: String, permanent: Boolean = false) {
        writableDatabase.execSQL("UPDATE rides SET sync_state=?,error=? WHERE id=?", arrayOf(if(permanent) "CONFLICT" else "SYNC_ERROR",code.take(80),id))
    }
    @Synchronized fun quarantine(deviceId: String) { writableDatabase.execSQL("UPDATE rides SET sync_state='QUARANTINED',error='device_revoked' WHERE device_id=? AND sync_state!='SYNCED'", arrayOf(deviceId)) }
    companion object {
        @Volatile private var instance: RideStore? = null
        fun get(context: Context): RideStore = instance ?: synchronized(this) { instance ?: RideStore(context.applicationContext).also { instance=it } }
        fun meters(a: Double,b: Double,c: Double,d: Double): Double {
            val x = sin(Math.toRadians(c-a)/2).pow(2) + cos(Math.toRadians(a))*cos(Math.toRadians(c))*sin(Math.toRadians(d-b)/2).pow(2)
            return 6371000 * 2 * asin(sqrt(x.coerceIn(0.0,1.0)))
        }
    }
}
