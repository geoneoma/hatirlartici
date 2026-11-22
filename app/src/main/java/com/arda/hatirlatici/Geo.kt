package com.arda.hatirlatici

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

object Geo {
    private const val RADIUS_M = 150f

    private fun pi(ctx: Context): PendingIntent {
        val i = Intent(ctx, GeofenceReceiver::class.java)
        // Geofence için PendingIntent değiştirilebilir (mutable) olmak zorunda
        return PendingIntent.getBroadcast(
            ctx, 777, i, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun register(ctx: Context, r: Reminder) {
        if (!r.isLocation() || r.done) return
        val transition = if (r.trigger == "EXIT") Geofence.GEOFENCE_TRANSITION_EXIT
        else Geofence.GEOFENCE_TRANSITION_ENTER
        val g = Geofence.Builder()
            .setRequestId("r" + r.id)
            .setCircularRegion(r.lat, r.lng, RADIUS_M)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(transition)
            .build()
        val req = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofence(g)
            .build()
        try {
            LocationServices.getGeofencingClient(ctx).addGeofences(req, pi(ctx))
                .addOnFailureListener { e ->
                    Diag.record(ctx, "konum kaydı başarısız: " + (e.message ?: ""))
                }
        } catch (e: SecurityException) {
            Diag.record(ctx, "konum izni yok, konum hatırlatması kurulamadı")
        }
    }

    fun remove(ctx: Context, id: Int) {
        try {
            LocationServices.getGeofencingClient(ctx).removeGeofences(listOf("r" + id))
        } catch (e: Exception) {
            // önemli değil
        }
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val ev = GeofencingEvent.fromIntent(intent) ?: return
        if (ev.hasError()) {
            Diag.record(ctx, "konum hatası: " + ev.errorCode)
            return
        }
        val list = ev.triggeringGeofences ?: return
        for (g in list) {
            val id = g.requestId.removePrefix("r").toIntOrNull() ?: continue
            val r = Store.find(ctx, id) ?: continue
            if (r.done) continue
            Diag.record(ctx, "konum tetiklendi")
            Notifier.show(ctx, r)
            Geo.remove(ctx, id)
            if (!r.persistent) r.done = true
            r.nagCount = 0
            Store.update(ctx, r)
            if (r.persistent) Scheduler.nag(ctx, r, 5)
        }
    }
}
