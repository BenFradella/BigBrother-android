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
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.jvm.javaClass
import java.io.*
import java.net.Socket
import java.net.UnknownHostException


class MapsActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var mFusedLocationProviderClient: FusedLocationProviderClient

    private lateinit var mNfcAdapter: NfcAdapter
    private lateinit var mPendingIntent: PendingIntent

    private var mFinePermission = Manifest.permission.ACCESS_FINE_LOCATION
    private var mCoarsePermission = Manifest.permission.ACCESS_COARSE_LOCATION
    private var mLocationPermissionsGranted = false
    private val mLocationPermissionRequestCode = 1234

    private lateinit var mZone: ImageButton

    private var socket: Socket? = null
    private val SERVERPORT = 6969
    private val SERVER_IP = "206.189.199.185"

    private var arrZone: MutableList<Circle> = ArrayList()
    private var arrBigBrother: MutableList<BigBrother> = ArrayList()

    private data class BigBrother(val name: String) {
        lateinit var location: LatLng
    }

    inner class ClientThread : Runnable {
        override fun run() {
            try {
//                val serverAddr: InetAddress = InetAddress.getByName(SERVER_IP)

                socket = Socket(SERVER_IP, SERVERPORT)

            } catch (e1: UnknownHostException) {
                e1.printStackTrace()
            } catch (e1: IOException) {
                e1.printStackTrace()
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maps)

        mZone = findViewById(R.id.circle_button)

        getLocationPermissions()

        mNfcAdapter = NfcAdapter.getDefaultAdapter(this)
        mPendingIntent = PendingIntent.getActivity(
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
        mNfcAdapter.enableForegroundDispatch(this, mPendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        mNfcAdapter.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent){
        val tag = getTagInfo(intent)
        if ( tag != null ) {
            Toast.makeText(applicationContext, "Scanned tag: $tag", Toast.LENGTH_LONG).show()
            // handle a new BigBrother device with name == tag
            arrBigBrother.add(BigBrother(tag))
                // todo - get the last known location of the device from the server
                // todo - and tell the server where the device is allowed to be
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
        val permissions = arrayOf(mFinePermission, mCoarsePermission)

        if (ContextCompat.checkSelfPermission(this.applicationContext, mFinePermission) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this.applicationContext, mCoarsePermission) == PackageManager.PERMISSION_GRANTED) {
            mLocationPermissionsGranted = true
            initMap()
        } else {
            ActivityCompat.requestPermissions(this, permissions, mLocationPermissionRequestCode)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        mLocationPermissionsGranted = false

        if (requestCode == mLocationPermissionRequestCode) {
            if (grantResults.isNotEmpty()) {
                for (permission in grantResults) {
                    if (permission != PackageManager.PERMISSION_GRANTED) {
                        mLocationPermissionsGranted = false
                        return
                    }
                }
                mLocationPermissionsGranted = true
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

        if (mLocationPermissionsGranted) {
            getDeviceLatLng()

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            mMap.isMyLocationEnabled = true
            mMap.uiSettings.isMyLocationButtonEnabled = false
        }

        mZone.setOnClickListener { drawCircle() }
    }

    private fun drawCircle() {
        arrZone.add(
            mMap.addCircle(
                CircleOptions()
                    .center(mMap.cameraPosition.target)
                    .radius(calculateVisibleWidth()/3.14)
                    .strokeColor(0x77ff0000)
                    .fillColor(0x7700ff00)
                    .clickable(true)
            )
        )
        mMap.setOnCircleClickListener { circle ->
            mZone.setImageResource(R.drawable.ic_delete)

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
                mZone.setOnClickListener { drawCircle() }
                mZone.setImageResource(R.drawable.ic_circle_green)
            }

            mZone.setOnClickListener {
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

    private fun getDeviceLatLng() {
        mFusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)

        try {
            if (mLocationPermissionsGranted) {

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
