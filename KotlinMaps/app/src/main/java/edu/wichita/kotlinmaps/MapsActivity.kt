package edu.wichita.kotlinmaps

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng

import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Enumeration


class MapsActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var mFusedLocationProviderClient: FusedLocationProviderClient

    private var mFinePermission = Manifest.permission.ACCESS_FINE_LOCATION
    private var mCoarsePermission = Manifest.permission.ACCESS_COARSE_LOCATION
    private var mLocationPermissionsGranted = false
    private val mLocationPermissionRequestCode = 1234

    private lateinit var mZone: ImageButton

    var arrZone: MutableList<Circle> = ArrayList()

    private class BigBrother(IpAddr: String) {
        val ipAddress = IpAddr
        lateinit var zone: Circle
        lateinit var location: LatLng

        fun sendCircle() {
            //tell the device what the center and radius of it's Circle are
        }
    }


    private fun getLocationPermissions() {
        var permissions = arrayOf(mFinePermission, mCoarsePermission)

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

    private lateinit var serverSocket: ServerSocket

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maps)

        mZone = findViewById(R.id.circle_button)

        getLocationPermissions()



        val socketServerThread = Thread(SocketServerThread())
        socketServerThread.start()
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            serverSocket.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private class SocketServerThread : Thread() {
        val socketServerPORT = 8080
        var count = 0

        override fun run() {
            try {
                serverSocket = ServerSocket(socketServerPORT)

                while (true) {
                    val socket: Socket = serverSocket.accept()
                    count++

                    val socketServerReplyThread = SocketServerReplyThread(
                        socket, count
                    )
                    socketServerReplyThread.run()
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private class SocketServerReplyThread(socket: Socket, c: Int) : Thread() {
        private var hostThreadSocket = socket
        var cnt = c

        override fun run() {
            val outputStream: OutputStream
            val msgReply = "Hello from Android, you are #$cnt"

            try {
                outputStream = hostThreadSocket.getOutputStream()
                val printStream = PrintStream(outputStream)
                printStream.print(msgReply)
                printStream.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }
}
