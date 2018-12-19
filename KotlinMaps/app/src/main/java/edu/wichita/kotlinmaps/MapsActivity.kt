package edu.wichita.kotlinmaps

import android.Manifest
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.content.pm.PackageManager
import android.location.Location
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat

import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices.*
import com.google.android.gms.tasks.Task
import android.widget.Toast
import android.util.Log
import android.support.annotation.NonNull
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.OnCompleteListener



class MapsActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var mFusedLocationProviderClient: FusedLocationProviderClient

    private var mFinePermission = Manifest.permission.ACCESS_FINE_LOCATION
    private var mCoarsePermission = Manifest.permission.ACCESS_COARSE_LOCATION
    private var mLocationPermissionsGranted = false
    private val mLocationPermissionRequestCode = 1234

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maps)

        getLocationPermissions()
    }

    private fun initMap() {
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment?
        mapFragment!!.getMapAsync(this)
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

    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera. In this case,
     * we just add a marker near Sydney, Australia.
     * If Google Play services is not installed on the device, the user will be prompted to install
     * it inside the SupportMapFragment. This method will only be triggered once the user has
     * installed Google Play services and returned to the app.
     */
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
