package edu.wichita.kotlinmaps

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color.*
import android.location.Location
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.*
import android.os.Build
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.app.NotificationCompat
import android.support.v4.app.NotificationManagerCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.widget.ImageButton
import android.widget.Toast
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import kotlin.jvm.javaClass
import java.io.*
import java.lang.Thread.sleep
import java.net.Socket
import kotlin.math.absoluteValue


class MapsActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var mFusedLocationProviderClient: FusedLocationProviderClient

    private lateinit var aNfcAdapter: NfcAdapter
    private lateinit var iPendingIntent: PendingIntent

    private var pFinePermission = Manifest.permission.ACCESS_FINE_LOCATION
    private var pCoarsePermission = Manifest.permission.ACCESS_COARSE_LOCATION
    private var bLocationPermissionsGranted = false
    private val nLocationPermissionRequestCode = 1234

    private lateinit var ibZone: ImageButton

    private var socket: Socket? = null
    private val thread = Thread(ClientThread())
    private val serverPort = 6969
    private val serverIp = "206.189.199.185"
    private var bDisconnect = false

    private var arrZone: MutableList<Circle> = ArrayList()
    private var arrBigBrother: MutableList<BigBrother> = ArrayList()
    private var mapPolyLines: MutableMap<String, Polyline> = HashMap()


    private inner class BigBrother(val name: String) {
        lateinit var location: LatLng
        val zone: MutableMap< String, Pair<LatLng, Double> > = HashMap()
        var status: Int = BLUE
        var distOutsideZone: Double? = 0.0

        //distance (in meters) to warn that a device is about to leave the zone
        private val warnDistance = -5.0

        init {
            mapPolyLines[this.name] =
                mMap.addPolyline( PolylineOptions()
                    .color(BLUE)
                    .endCap(CustomCap(
                        BitmapDescriptorFactory.fromResource(R.mipmap.ic_line_end), 40f
                    ))
                )
        }

        fun updateLocation( location: LatLng ) {
            this.location = location

            val points = mapPolyLines[this.name]!!.points
            points.add(location)
            mapPolyLines[this.name]!!.points = points
        }
        fun updateLocation( locationHistory: List<LatLng> ) {
            this.location = locationHistory.last()

            val points = mapPolyLines[this.name]!!.points
            points.addAll(locationHistory)
            mapPolyLines[this.name]!!.points = points
        }

        fun updateStatus() {
            var fLeastDelta: Double? = null
            var fCurrentDelta: Double
            for ( circle in arrZone ) {
                // find the least delta between the device and a zone edge
                fCurrentDelta = circleEdgeDelta(circle, this.location)
                if ( fLeastDelta == null || fCurrentDelta < fLeastDelta ) {
                    fLeastDelta = fCurrentDelta
                }
                if ( fLeastDelta < warnDistance ) {
                    // we don't need to find the least. Just need to know it isn't outside/nearly outside the circle
                    break
                }
            }

            when {
                ( fLeastDelta == null ) -> {
                    this.status = BLUE
                    this.distOutsideZone = 0.0
                }
                ( fLeastDelta < warnDistance ) -> {
                    this.status = GREEN
                    this.distOutsideZone = 0.0
                }
                ( fLeastDelta in warnDistance..0.0 ) -> {
                    this.status = YELLOW
                    this.distOutsideZone = 0.0
                }
                else -> {
                    this.status = RED
                    this.distOutsideZone = fLeastDelta
                }
            }
            mapPolyLines[this.name]!!.color = this.status
        }

        fun buildZone() {
            val circleNames = ArrayList<String>()

            for ( circle in arrZone ) {
                this.zone[circle.id] = Pair(circle.center, circle.radius)
                circleNames.add(circle.id)
            }
            for ( oldCircle in this.zone.keys ) {
                if ( oldCircle !in circleNames ) {
                    this.zone.remove(oldCircle)
                }
            }
        }
    }

    inner class ClientThread : Runnable {
        override fun run() = try {
            socket = Socket(serverIp, serverPort)

            val inStream = DataInputStream(socket!!.getInputStream())
            val outStream = DataOutputStream(socket!!.getOutputStream())

            // thread will sit here until it's able to send the message, insuring we have a stable connection
            outStream.writeUTF("Hello from an observer")

            while (!bDisconnect) {
                for (bbDevice in arrBigBrother) {
                    //get device locations and update map
                    outStream.writeUTF("getLocation ${bbDevice.name}")
                    val sLocationHistory = inStream.readUTF()
                    val arrLocationHistory: MutableList<LatLng> = ArrayList()

                    for (sLocation in sLocationHistory.split("\n")) {
                        if (sLocation != "") {
                            val arrLatLng = sLocation.split(',')
                            var dLat = 0.0
                            var dLon = 0.0
                            when (arrLatLng[0].last()) {
                                'N' -> dLat = arrLatLng[0].dropLast(1).toDouble()
                                'S' -> dLat = -arrLatLng[0].dropLast(1).toDouble()
                            }
                            when (arrLatLng[1].last()) {
                                'E' -> dLon = arrLatLng[1].dropLast(1).toDouble()
                                'W' -> dLon = -arrLatLng[1].dropLast(1).toDouble()
                            }
                            val llLocation = LatLng(dLat, dLon)
                            arrLocationHistory.add(llLocation)
                        }
                    }
                    runOnUiThread {
                        bbDevice.updateLocation(arrLocationHistory)
                        bbDevice.updateStatus()
                        bbDevice.buildZone()
                        if ( bbDevice.status == RED ) {
                            //todo - send push notification
                            pushNotification(
                                "Device outside zone!",
                                "${bbDevice.name} is ${bbDevice.distOutsideZone}" +
                                        " meters outside the area you have set",
                                bbDevice.name
                            )
                        }
                    }

                    // build string and update server with zone data
                    val circleList = ArrayList<String>()
                    for ( circle in bbDevice.zone.values ) {
                        val sLatitude = if ( circle.first.latitude < 0 ) {
                            circle.first.latitude.absoluteValue.toString() + 'S'
                        } else {
                            circle.first.latitude.toString() + 'N'
                        }
                        val sLongitude = if ( circle.first.longitude < 0 ) {
                            circle.first.longitude.absoluteValue.toString() + 'W'
                        } else {
                            circle.first.longitude.toString() + 'E'
                        }

                        circleList.add("$sLatitude,$sLongitude,${circle.second}")
                    }
                    if ( circleList.size > 0 ) {
                        outStream.writeUTF("setZone ${bbDevice.name} ${circleList.joinToString(separator = "\n")}")
                    }
                }
                sleep(1000) // only ping server once per second
            }

            outStream.writeUTF("Goodbye")
            inStream.close()
            outStream.close()
            socket!!.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showToast(toast: String) {
        runOnUiThread { Toast.makeText(this@MapsActivity, toast, Toast.LENGTH_SHORT).show() }
    }

    private fun pushNotification(title: String, message: String, deviceID: String, CHANNEL_ID: String="Big Brother") {
        val notificationId: Int = deviceID.hashCode()

        val intent = Intent(this, AlertDialog::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, intent, 0)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_icon_red)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(this)) {
            notify(notificationId, builder.build())
        }
    }
    private fun createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.app_name)
            val descriptionText = getString(R.string.app_description)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel("Big Brother", name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createNotificationChannel()
        setContentView(R.layout.activity_maps)
        ibZone = findViewById(R.id.circle_button)

        getLocationPermissions()

        aNfcAdapter = NfcAdapter.getDefaultAdapter(this)
        iPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(
                this,
                javaClass
            ).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            0
        )

        thread.start()
    }

    override fun onResume() {
        super.onResume()
        createNotificationChannel()
        aNfcAdapter.enableForegroundDispatch(this, iPendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        aNfcAdapter.disableForegroundDispatch(this)
    }

    override fun onDestroy() {
        bDisconnect = true
        thread.join(5000)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent){
        /**
         * get the names of BigBrother devices from NFC tags and create corresponding objects
         */
        val tag = getTagInfo(intent)
        if ( tag != null ) {
            Toast.makeText(applicationContext, "Scanned tag: $tag", Toast.LENGTH_LONG).show()
            try {
                arrBigBrother.add(BigBrother(tag))
            } catch (e: Exception) {
                showToast("$e")
            }
        }
    }
    private fun getTagInfo(intent: Intent): String? {
        val tag: Tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        val techList = tag.techList

        for ( i in techList ) when ( i ) {
            Ndef::class.java.name -> {
                val ndef = Ndef.get(tag)

                try{
                    ndef.connect()

                    val messages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)

                    if (messages != null) {
                        val ndefMessages = Array<NdefMessage?>(messages.size){null}
                        for ( index in 0 until messages.size) {
                            ndefMessages[index] = messages[index] as NdefMessage
                        }

                        val record: NdefRecord = ndefMessages[0]!!.records[0]
                        val msg = record.payload.drop(3).toByteArray()
                        // Drop the first three characters. They're extras saying that they're the record's payload and
                        // that they use english characters, I think.
                        val payloadText = String(msg, charset("UTF-8"))

                        ndef.close()

                        return payloadText
                    }
                }
                catch (e: Exception) {
                    Toast.makeText(applicationContext, "Cannot Read From Ndef Tag.", Toast.LENGTH_LONG).show()
                }
            }
        }
        return null
    }

    private fun getLocationPermissions() {
        val permissions = arrayOf(pFinePermission, pCoarsePermission)

        if (ContextCompat.checkSelfPermission(this.applicationContext, pFinePermission) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this.applicationContext, pCoarsePermission) == PackageManager.PERMISSION_GRANTED) {
            bLocationPermissionsGranted = true
            initMap()
        } else {
            ActivityCompat.requestPermissions(this, permissions, nLocationPermissionRequestCode)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        bLocationPermissionsGranted = false

        if (requestCode == nLocationPermissionRequestCode) {
            if (grantResults.isNotEmpty()) {
                for (permission in grantResults) {
                    if (permission != PackageManager.PERMISSION_GRANTED) {
                        bLocationPermissionsGranted = false
                        return
                    }
                }
                bLocationPermissionsGranted = true
                initMap()
            }
        }
    }

    private fun initMap() {
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment?
        mapFragment!!.getMapAsync(this)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap

        if (bLocationPermissionsGranted) {
            getDeviceLatLng()

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            mMap.isMyLocationEnabled = true
            mMap.uiSettings.isMyLocationButtonEnabled = false
        }

        ibZone.setOnClickListener { drawCircle() }
    }

    private fun drawCircle() {
        arrZone.add(
            mMap.addCircle(
                CircleOptions()
                    .center(mMap.cameraPosition.target)
                    .radius(calculateVisibleWidth()/3.14)   // visible width by itself would make the radius too big
                    .strokeColor(0x33ff0000)
                    .fillColor(0x3300ff00)
                    .clickable(true)
            )
        )
        mMap.setOnCircleClickListener { circle ->
            ibZone.setImageResource(R.drawable.ic_delete)

            for ( zone in arrZone ) {
                zone.isClickable = false
            }
            circle.fillColor = 0x330000ff

            fun backToNormal() {
                circle.fillColor = 0x3300ff00
                for ( zone in arrZone ) {
                    zone.isClickable = true
                }

                mMap.setOnMapClickListener(null)
                mMap.setOnMapLongClickListener(null)
                ibZone.setOnClickListener { drawCircle() }
                ibZone.setImageResource(R.drawable.ic_circle_green)
            }

            ibZone.setOnClickListener {
                backToNormal()

                arrZone.remove(circle)
                circle.remove()
            }

            mMap.setOnMapClickListener { position ->
                val center = circle.center
                val distance = FloatArray(1)

                Location.distanceBetween(
                    position.latitude,
                    position.longitude,
                    center.latitude,
                    center.longitude,
                    distance
                )

                circle.radius = distance[0].toDouble()

                backToNormal()
            }

            mMap.setOnMapLongClickListener { position ->
                circle.center = position

                backToNormal()
            }
        }
    }

    private fun calculateVisibleWidth(): Double {
        val visibleRegion = mMap.projection.visibleRegion
        val farRight = visibleRegion.farRight
        val farLeft = visibleRegion.farLeft
        val nearRight = visibleRegion.nearRight
        val nearLeft = visibleRegion.nearLeft

        if ( mMap.cameraPosition.tilt.toDouble() == 0.0 ) {
            val midWidth = FloatArray(1)

            Location.distanceBetween(
                (nearLeft.latitude + farLeft.latitude) / 2,
                (nearLeft.longitude + farLeft.longitude) / 2,
                (nearRight.latitude + farRight.latitude) / 2,
                (nearRight.longitude + farRight.longitude) / 2,
                midWidth
            )

            return midWidth[0].toDouble()
        }
        else {
            val nearWidth = FloatArray(1)
            val farWidth = FloatArray(1)
            val fromNear = FloatArray(1)
            val fromFar = FloatArray(1)
            val center = mMap.cameraPosition.target

            Location.distanceBetween(
                nearLeft.latitude,
                nearLeft.longitude,
                nearRight.latitude,
                nearRight.longitude,
                nearWidth
            )
            Location.distanceBetween(
                farLeft.latitude,
                farLeft.longitude,
                farRight.latitude,
                farRight.longitude,
                farWidth
            )
            Location.distanceBetween(
                center.latitude,
                center.longitude,
                (nearLeft.latitude + nearRight.latitude) / 2,
                (nearLeft.longitude + nearRight.longitude) / 2,
                fromNear
            )
            Location.distanceBetween(
                center.latitude,
                center.longitude,
                (farLeft.latitude + farRight.latitude) / 2,
                (farLeft.longitude + farRight.longitude) / 2,
                fromFar
            )

            val height = fromNear[0] + fromFar[0]

            // weighted average between nearWidth and farWidth to get the width at the center of the screen
            val midWidth = (fromFar[0]/height)*nearWidth[0] + (fromNear[0]/height)*farWidth[0]

            return midWidth.toDouble()
        }
    }

    private fun circleEdgeDelta(circle: Circle, location: LatLng): Double {
        /**
         * returns the delta (in meters) between a location and the edge of a circle
         * negative values are inside the circle, 0 is at the edge, and positive is outside the circle
         */
        val arrDistance = FloatArray(1)

        Location.distanceBetween(
            circle.center.latitude,
            circle.center.longitude,
            location.latitude,
            location.longitude,
            arrDistance
        )
        val distance = arrDistance[0]

        return (distance - circle.radius)
    }

    private fun getDeviceLatLng() {
        mFusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)

        try {
            if (bLocationPermissionsGranted) {

                val location = mFusedLocationProviderClient.lastLocation
                location.addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val currentLocation = task.result

                        moveCamera(LatLng(currentLocation!!.latitude, currentLocation.longitude), 15f)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e("MapActivity", "getDeviceLocation: SecurityException: " + e.message)
        }
    }

    private fun moveCamera(latLng: LatLng, zoom: Float) {
        Log.d("MapActivity","moveCamera: moving the camera to: lat: " + latLng.latitude + ", lng: " + latLng.longitude)
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, zoom))
    }
}
