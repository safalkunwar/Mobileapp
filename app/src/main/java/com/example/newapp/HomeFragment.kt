package com.example.newapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import org.osmdroid.bonuspack.routing.OSRMRoadManager
import org.osmdroid.bonuspack.routing.Road
import org.osmdroid.bonuspack.routing.RoadManager
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.*
import kotlinx.coroutines.*

class HomeFragment : Fragment(), LocationListener {

    private lateinit var mapView: MapView
    private lateinit var locationManager: LocationManager
    private lateinit var trackingButton: Button
    private lateinit var statusText: TextView
    private lateinit var speedText: TextView
    private lateinit var databaseRef: DatabaseReference
    private var currentMarker: Marker? = null
    private var isTracking = false
    private var driverId: String? = null
    private lateinit var searchInput: EditText
    private lateinit var directionDialog: CardView
    private lateinit var fromLocation: EditText
    private lateinit var toLocation: EditText
    private lateinit var noticeText: TextView
    private var currentRoute: Polyline? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val UPDATE_INTERVAL = 2000L // 2 seconds
        private const val DEFAULT_ZOOM = 15.0
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Initialize OSMDroid configuration
        Configuration.getInstance().load(
            requireContext(),
            PreferenceManager.getDefaultSharedPreferences(requireContext())
        )

        val view = inflater.inflate(R.layout.fragment_home, container, false)

        // Initialize views
        mapView = view.findViewById(R.id.map_container)
        trackingButton = view.findViewById(R.id.tracking_button)
        statusText = view.findViewById(R.id.status_text)
        speedText = view.findViewById(R.id.speed_text)
        searchInput = view.findViewById(R.id.search_input)
        directionDialog = view.findViewById(R.id.direction_dialog)
        fromLocation = view.findViewById(R.id.from_location)
        toLocation = view.findViewById(R.id.to_location)
        noticeText = view.findViewById(R.id.notice_text)

        // Initialize map
        setupMap()

        // Initialize location services
        locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Initialize Firebase
        databaseRef = FirebaseDatabase.getInstance().reference.child("BusLocation")
        
        // Get driver ID from shared preferences
        driverId = requireContext().getSharedPreferences("MyPrefs", 0)
            .getString("driverId", null)

        // Set up tracking button
        trackingButton.setOnClickListener {
            if (isTracking) {
                stopTracking()
            } else {
                startTracking()
            }
        }

        // Set up search functionality
        setupSearch()

        // Set up direction button
        view.findViewById<Button>(R.id.direction_button).setOnClickListener {
            toggleDirectionDialog()
        }

        // Set up get directions button
        view.findViewById<Button>(R.id.get_directions_button).setOnClickListener {
            calculateRoute()
        }

        return view
    }

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        val mapController = mapView.controller
        mapController.setZoom(DEFAULT_ZOOM)
        
        // Set initial position (e.g., Kathmandu)
        val startPoint = GeoPoint(27.7172, 85.3240)
        mapController.setCenter(startPoint)
    }

    private fun updateLocation(location: Location) {
        val latitude = location.latitude
        val longitude = location.longitude
        val speed = location.speed * 3.6 // Convert m/s to km/h
        val timestamp = Date().time

        // Update map
        val currentPosition = GeoPoint(latitude, longitude)
        mapView.controller.animateTo(currentPosition)

        // Update marker
        if (currentMarker == null) {
            currentMarker = Marker(mapView).apply {
                title = "Current Location"
                mapView.overlays.add(this)
            }
        }
        currentMarker?.position = currentPosition
        mapView.invalidate()

        // Update speed display
        speedText.text = "Speed: %.1f km/h".format(speed)

        // Upload to Firebase
        driverId?.let { id ->
            val locationData = hashMapOf(
                "latitude" to latitude,
                "longitude" to longitude,
                "timestamp" to timestamp
            )
            databaseRef.child(id).child(timestamp.toString()).setValue(locationData)
        }
    }

    private fun startTracking() {
        if (checkLocationPermission()) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                UPDATE_INTERVAL,
                1f,
                this
            )
            isTracking = true
            trackingButton.text = "Stop Tracking"
            statusText.text = "Status: Tracking"
            noticeText.text = "Tracking is active - Your location is being shared"
        }
    }

    private fun stopTracking() {
        locationManager.removeUpdates(this)
        isTracking = false
        trackingButton.text = "Start Tracking"
        statusText.text = "Status: Not Tracking"
        noticeText.text = "Tracking is stopped - Click Start Tracking to begin sharing your location"
    }

    private fun checkLocationPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
            return false
        }
        return true
    }

    // LocationListener methods
    override fun onLocationChanged(location: Location) {
        updateLocation(location)
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // Fragment lifecycle methods
    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        if (isTracking) {
            stopTracking()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDetach()
        coroutineScope.cancel()
    }

    private fun setupSearch() {
        searchInput.setOnEditorActionListener { v, actionId, event ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                performSearch(searchInput.text.toString())
                true
            } else false
        }
    }

    private fun performSearch(query: String) {
        coroutineScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    val geocoder = android.location.Geocoder(requireContext())
                    geocoder.getFromLocationName(query, 1)
                }
                
                results?.firstOrNull()?.let { location ->
                    val point = GeoPoint(location.latitude, location.longitude)
                    mapView.controller.animateTo(point)
                    
                    // Add marker
                    val marker = Marker(mapView)
                    marker.position = point
                    marker.title = location.featureName
                    mapView.overlays.add(marker)
                    mapView.invalidate()
                }
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    private fun toggleDirectionDialog() {
        if (directionDialog.visibility == View.VISIBLE) {
            directionDialog.visibility = View.GONE
        } else {
            directionDialog.visibility = View.VISIBLE
        }
    }

    private fun calculateRoute() {
        coroutineScope.launch {
            try {
                val fromAddress = fromLocation.text.toString()
                val toAddress = toLocation.text.toString()

                // Get coordinates for addresses
                val geocoder = android.location.Geocoder(requireContext())
                val fromResults = withContext(Dispatchers.IO) {
                    geocoder.getFromLocationName(fromAddress, 1)
                }
                val toResults = withContext(Dispatchers.IO) {
                    geocoder.getFromLocationName(toAddress, 1)
                }

                val fromPoint = fromResults?.firstOrNull()?.let { 
                    GeoPoint(it.latitude, it.longitude) 
                }
                val toPoint = toResults?.firstOrNull()?.let { 
                    GeoPoint(it.latitude, it.longitude) 
                }

                if (fromPoint != null && toPoint != null) {
                    // Remove existing route
                    currentRoute?.let { mapView.overlays.remove(it) }

                    // Calculate new route
                    val roadManager = OSRMRoadManager(requireContext(), "OsmDroid")
                    val waypoints = arrayListOf(fromPoint, toPoint)
                    val road = withContext(Dispatchers.IO) {
                        roadManager.getRoad(waypoints)
                    }

                    // Draw route
                    val roadOverlay = RoadManager.buildRoadOverlay(road)
                    mapView.overlays.add(roadOverlay)
                    currentRoute = roadOverlay
                    mapView.invalidate()

                    // Hide dialog
                    directionDialog.visibility = View.GONE
                }
            } catch (e: Exception) {
                // Handle error
            }
        }
    }
} 