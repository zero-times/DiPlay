package com.shilapi.xcertplay.riding

import android.content.Context
import com.shilapi.xcertplay.host.R

object RideSyncStatus {
    fun text(context:Context,ride:RideSession?):String {
        if(ride?.syncState=="SYNCING") return context.getString(R.string.riding_sync_progress,ride.ack.toString(),ride.count.toString())
        val label=when(ride?.syncState) {
            "SYNCED" -> if(ride.count==0L) R.string.riding_synced_no_gps else R.string.riding_synced
            "QUARANTINED" -> R.string.riding_quarantined
            "SYNC_ERROR" -> R.string.riding_sync_error
            "CONFLICT" -> when(ride.error) {
                "goal_required" -> R.string.riding_sync_goal_required
                "ride_overlap" -> R.string.riding_sync_overlap
                "session_deleted" -> R.string.riding_sync_deleted
                "points_incomplete" -> R.string.riding_sync_incomplete
                "no_valid_points" -> R.string.riding_sync_no_gps_retry
                "invalid_finish" -> if(ride.count==0L) R.string.riding_sync_no_gps_retry else R.string.riding_sync_data_error
                "invalid_point","invalid_session","invalid_pauses","point_conflict","session_conflict" -> R.string.riding_sync_data_error
                else -> R.string.riding_sync_retry
            }
            else -> R.string.riding_pending
        }
        return context.getString(label)
    }
}
