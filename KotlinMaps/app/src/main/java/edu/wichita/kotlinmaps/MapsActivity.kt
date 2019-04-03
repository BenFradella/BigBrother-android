package edu.wichita.kotlinmaps

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.*
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
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
    private val SERVERPORT = 6969
    private val SERVER_IP = "206.189.199.185"
    private var bDisconnect = false

    private var arrZone: MutableList<Circle> = ArrayList()
    private var arrBigBrother: MutableList<BigBrother> = ArrayList()
    private var mapPolyLines: MutableMap<String, Polyline> = HashMap()

    private inner class BigBrother(val name: String) {
        lateinit var location: LatLng

        init {
            mapPolyLines[this.name] =
                mMap.addPolyline( PolylineOptions()
                    .color(0x770000ff) // half-transparent blue
                    .endCap(CustomCap(
                        BitmapDescriptorFactory.fromResource(R.mipmap.ic_line_end), 40f
                    ))
                )
        }

        fun update( location: LatLng ) {
            this.location = location

            val points = mapPolyLines[this.name]!!.points
            points.add(location)
            mapPolyLines[this.name]!!.points = points
        }
        fun update( locationHistory: List<LatLng> ) {
            this.location = locationHistory.last()

            val points = mapPolyLines[this.name]!!.points
            points.addAll(locationHistory)
            mapPolyLines[this.name]!!.points = points
        }
    }

    inner class ClientThread : Runnable {
        override fun run() = try {
            socket = Socket(SERVER_IP, SERVERPORT)

            val inStream = DataInputStream(socket!!.getInputStream())
            val outStream = DataOutputStream(socket!!.getOutputStream())

            // thread will sit here until it's able to send the message, insuring we have a stable connection
            outStream.writeUTF("Hello from an observer")

            var loops = 0
            while ( ! bDisconnect ) {
                for ( bbDevice in arrBigBrother ) {
                    /**
                     * get device locations and update map
                     */
                    outStream.writeUTF("getLocation ${bbDevice.name}")
                    val sLocationHistory = inStream.readUTF()
                    val arrLocationHistory: MutableList<LatLng> = ArrayList()

                    for ( sLocation in sLocationHistory.split("\n") ) {
                        if ( sLocation != "" ) {
                            val arrLatLng = sLocation.split(',')
                            var dLat = 0.0
                            var dLon = 0.0
                            when ( arrLatLng[0].last() ) {
                                'N' -> dLat = arrLatLng[0].dropLast(1).toDouble()
                                'S' -> dLat = -arrLatLng[0].dropLast(1).toDouble()
                            }
                            when ( arrLatLng[1].last() ) {
                                'E' -> dLon = arrLatLng[1].dropLast(1).toDouble()
                                'W' -> dLon = -arrLatLng[1].dropLast(1).toDouble()
                            }
                            val llLocation = LatLng(dLat, dLon)
                            arrLocationHistory.add(llLocation)
                        }
                    }
                    runOnUiThread {
                        bbDevice.update(arrLocationHistory)
                    }

                    var bDeviceInsideZone = false
                    for ( circle in arrZone ) {
                        // check if device is in any zones
                        if ( circleContains(circle, bbDevice) ) {
                            bDeviceInsideZone = true
                            break
                        }
                    }
                    if ( ! bDeviceInsideZone ) {
                        runOnUiThread {
                            mapPolyLines[bbDevice.name]!!.setColor(0x77ff0000) // half-transparent red
                            // todo - send push notification
                        }
                    }
                }
                // todo - update server with zone data
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


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        Thread(ClientThread()).start()
    }

    override fun onResume() {
        super.onResume()
        aNfcAdapter.enableForegroundDispatch(this, iPendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        aNfcAdapter.disableForegroundDispatch(this)
    }

    override fun onDestroy() {
        bDisconnect = true
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
                    .strokeColor(0x77ff0000)
                    .fillColor(0x7700ff00)
                    .clickable(true)
            )
        )
        mMap.setOnCircleClickListener { circle ->
            ibZone.setImageResource(R.drawable.ic_delete)

            for ( zone in arrZone ) {
                zone.isClickable = false
            }
            circle.fillColor = 0x770000ff

            fun backToNormal() {
                circle.fillColor = 0x7700ff00
                for ( zone in arrZone ) {
                    zone.isClickable = true
                }

                mMap.setOnMapClickListener(null)
                mMap.setOnMapLongClickListener(null)
                ibZone.setOnClickListener { drawCircle() }
                ibZone.setImageResource(R.drawable.ic_circle_green)
            }

            ibZone.setOnClickListener {
                arrZone.remove(circle)
                circle.remove()

                backToNormal()
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

    private fun circleContains(circle: Circle, bbDevice: BigBrother): Boolean {
        val arrDistance = FloatArray(1)

        Location.distanceBetween(
            circle.center.latitude,
            circle.center.longitude,
            bbDevice.location.latitude,
            bbDevice.location.longitude,
            arrDistance
        )
        val distance = arrDistance[0]

        return distance <= circle.radius
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
