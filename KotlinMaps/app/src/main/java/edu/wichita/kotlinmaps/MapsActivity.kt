package edu.wichita.kotlinmaps

import android.Manifest
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat

import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices.*
import com.google.android.gms.tasks.Task
import android.widget.Toast
import android.util.Log
import android.support.annotation.NonNull
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.*
import com.google.android.gms.tasks.OnCompleteListener
import java.lang.Math.pow
import kotlin.math.pow


class MapsActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var mFusedLocationProviderClient: FusedLocationProviderClient

    private var mFinePermission = Manifest.permission.ACCESS_FINE_LOCATION
    private var mCoarsePermission = Manifest.permission.ACCESS_COARSE_LOCATION
    private var mLocationPermissionsGranted = false
    private val mLocationPermissionRequestCode = 1234

    private lateinit var mZone: ImageView


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maps)

        mZone = findViewById(R.id.zone_circle)

        getLocationPermissions()
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
            if (grantResults.size > 0) {
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

        mZone.setOnClickListener(object: View.OnClickListener {
            override fun onClick(view: View) {
                val zone: Circle = mMap.addCircle(
                    CircleOptions()
                        .center(mMap.cameraPosition.target)
                        .radius(calculateVisibleRadius())
                        .strokeColor(Color.RED)
                        .fillColor(Color.GREEN)
                        .clickable(true)
                )
            }
        })
    }

    fun calculateVisibleRadius(): Double {
        val distanceWidth = FloatArray(1)
        var visibleRegion = mMap.getProjection().getVisibleRegion()
        val farRight = visibleRegion.farRight
        val farLeft = visibleRegion.farLeft
        val nearRight = visibleRegion.nearRight
        val nearLeft = visibleRegion.nearLeft
        //calculate the distance between left <-> right of map on screen
        Location.distanceBetween( (farLeft.latitude + nearLeft.latitude) / 2, farLeft.longitude, (farRight.latitude + nearRight.latitude) / 2, farRight.longitude, distanceWidth )
        // visible radius is / 2  and /1000 in Km:
         return distanceWidth[0].toDouble()/3.14
    }

    private fun getDeviceLatLng() {
        mFusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)

        try {
            if (mLocationPermissionsGranted) {

                val location = mFusedLocationProviderClient.getLastLocation()
                location.addOnCompleteListener(object: OnCompleteListener<Location> {
                    override fun onComplete(task: Task<Location>) {
                        if (task.isSuccessful) {
                            val currentLocation = task.result

                            moveCamera(LatLng(currentLocation!!.latitude, currentLocation.longitude), 15f)
                        }
                    }
                })
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
