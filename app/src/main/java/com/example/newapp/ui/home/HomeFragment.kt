package com.example.newapp.ui.home
import android.location.Geocoder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.preference.PreferenceManager
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.newapp.R
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL
import org.osmdroid.bonuspack.routing.OSRMRoadManager
import org.osmdroid.bonuspack.routing.Road
import org.osmdroid.bonuspack.routing.RoadManager
import org.osmdroid.util.BoundingBox
import android.view.animation.Animation
import android.view.animation.AnimationUtils

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
    private var trackingInterval: Job? = null

    companion object {
        private const val TAG = "HomeFragment"
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val UPDATE_INTERVAL = 2000L // 2 seconds
        private const val DEFAULT_ZOOM = 15.0
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
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

        // Initialize Firebase
        databaseRef = FirebaseDatabase.getInstance().reference
        
        // Get driver ID from shared preferences
        driverId = requireContext().getSharedPreferences("MyPrefs", Context.MODE_PRIVATE)
            .getString("driverId", null)

        Log.d(TAG, "Driver ID from preferences: $driverId")

        // Initialize map
        setupMap()

        // Initialize location services
        locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Set up tracking button
        trackingButton.setOnClickListener {
            if (isTracking) {
                stopTracking()
            } else {
                startTracking()
            }
        }

        // Set up direction button
        view.findViewById<Button>(R.id.direction_button).setOnClickListener {
            directionDialog.visibility = View.VISIBLE
        }

        // Set up get directions button
        view.findViewById<Button>(R.id.get_directions_button).setOnClickListener {
            calculateRoute()
        }

        // Set up search functionality
        setupSearch()

        return view
    }

    private fun setupMap() {
        // Configure the map
        Configuration.getInstance().userAgentValue = requireContext().packageName
        
        mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setBuiltInZoomControls(true)
            
            controller.apply {
                // Set initial position (Kathmandu)
                setCenter(GeoPoint(27.7172, 85.3240))
                setZoom(DEFAULT_ZOOM)
            }

            // Set minimum and maximum zoom levels
            minZoomLevel = 4.0
            maxZoomLevel = 19.0
        }
    }

    private fun updateLocation(location: Location) {
        val timestamp = System.currentTimeMillis()
        val latitude = location.latitude
        val longitude = location.longitude
        val speed = location.speed * 3.6f // Convert m/s to km/h

        // Update marker
        val currentPosition = GeoPoint(latitude, longitude)
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

        // Get the non-null driverId or return early
        val id = driverId ?: return

        // Create location data
        val locationData = hashMapOf(
            "latitude" to latitude,
            "longitude" to longitude
        )

        // Update Firebase
        databaseRef.child(id)
            .child(timestamp.toString())
            .setValue(locationData)
            .addOnSuccessListener {
                Log.d(TAG, "Location updated successfully")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to update location", e)
            }
    }

    private fun startTracking() {
        if (driverId == null) {
            Toast.makeText(context, "Driver ID not found. Please login again.", Toast.LENGTH_LONG).show()
            return
        }

        if (checkLocationPermission()) {
            try {
                // Initialize Firebase reference
                databaseRef = FirebaseDatabase.getInstance().reference
                
                // Start location updates
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

                // Start sending location updates
                trackingInterval = coroutineScope.launch {
                    while (isActive && isTracking) {
                        try {
                            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { location ->
                                sendLocationToFirebase(location)
                            }
                            delay(UPDATE_INTERVAL)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in tracking interval", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting tracking", e)
                Toast.makeText(context, "Error starting tracking: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendLocationToFirebase(location: Location) {
        try {
            val busId = driverId ?: return
            val timestamp = System.currentTimeMillis().toString()

            // Create location data
            val locationUpdate = hashMapOf(
                "latitude" to location.latitude,
                "longitude" to location.longitude
            )

            // Update in Firebase under BusLocation/busId/timestamp
            databaseRef.child("BusLocation")
                .child(busId)
                .child(timestamp)
                .setValue(locationUpdate)
                .addOnSuccessListener {
                    Log.d(TAG, "Location updated for bus $busId")
                    // Update UI
                    updateBusMarker(location)
                    speedText.text = "Speed: %.1f km/h".format(location.speed * 3.6f)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to update location for bus $busId: ${e.message}")
                    Toast.makeText(context, "Failed to update location", Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending location to Firebase", e)
        }
    }

    private fun stopTracking() {
        try {
            locationManager.removeUpdates(this)
            trackingInterval?.cancel()
            trackingInterval = null
            isTracking = false
            trackingButton.text = "Start Tracking"
            statusText.text = "Status: Not Tracking"
            noticeText.text = "Tracking is stopped - Click Start Tracking to begin sharing your location"
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping tracking", e)
        }
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
        if (isTracking) {
            sendLocationToFirebase(location)
        }
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
        stopTracking()
        trackingInterval?.cancel()
        coroutineScope.cancel()
        mapView.onDetach()
    }

    private fun setupSearch() {
        // Create custom adapter for suggestions
        val suggestionsAdapter = object : ArrayAdapter<String>(
            requireContext(),
            R.layout.item_location_suggestion,
            mutableListOf()
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context)
                    .inflate(R.layout.item_location_suggestion, parent, false)
                
                val text = getItem(position)
                view.findViewById<TextView>(R.id.suggestion_text).text = text
                
                return view
            }
        }

        val suggestionsOverlay = view?.findViewById<FrameLayout>(R.id.suggestions_overlay)
        val suggestionsContainer = view?.findViewById<CardView>(R.id.search_results_container)
        val suggestionsList = view?.findViewById<ListView>(R.id.search_results_list)
        suggestionsList?.adapter = suggestionsAdapter

        fun showSuggestions() {
            suggestionsOverlay?.visibility = View.VISIBLE
            suggestionsContainer?.visibility = View.VISIBLE
        }

        fun hideSuggestions() {
            suggestionsOverlay?.visibility = View.GONE
            suggestionsContainer?.visibility = View.GONE
        }

        // Handle clicks outside the suggestions
        suggestionsOverlay?.setOnClickListener {
            hideSuggestions()
        }

        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString()
                if (query.length >= 2) {
                    fetchSuggestions(query, suggestionsAdapter)
                    showSuggestions()
                } else {
                    suggestionsAdapter.clear()
                    hideSuggestions()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        }

        // Add focus listeners
        searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && searchInput.text.length >= 2) {
                fetchSuggestions(searchInput.text.toString(), suggestionsAdapter)
                showSuggestions()
            }
        }

        fromLocation.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && fromLocation.text.length >= 2) {
                fetchSuggestions(fromLocation.text.toString(), suggestionsAdapter)
                showSuggestions()
            }
        }

        toLocation.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && toLocation.text.length >= 2) {
                fetchSuggestions(toLocation.text.toString(), suggestionsAdapter)
                showSuggestions()
            }
        }

        // Add text watchers
        searchInput.addTextChangedListener(textWatcher)
        fromLocation.addTextChangedListener(textWatcher)
        toLocation.addTextChangedListener(textWatcher)

        // Update suggestion selection handler
        suggestionsList?.setOnItemClickListener { _, _, position, _ ->
            val selectedLocation = suggestionsAdapter.getItem(position) ?: return@setOnItemClickListener
            
            when {
                fromLocation.hasFocus() -> fromLocation.setText(selectedLocation)
                toLocation.hasFocus() -> toLocation.setText(selectedLocation)
                else -> {
                    searchInput.setText(selectedLocation)
                    performSearch(selectedLocation)
                }
            }

            hideSuggestions()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view?.windowToken, 0)
        }
    }

    private fun fetchSuggestions(query: String, adapter: ArrayAdapter<String>) {
        coroutineScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    val geocoder = Geocoder(requireContext())
                    geocoder.getFromLocationName(query, 5)
                }

                adapter.clear()
                results?.forEach { address ->
                    adapter.add(address.getAddressLine(0))
                }
                adapter.notifyDataSetChanged()

                // Show suggestions container if we have results
                val suggestionsContainer = view?.findViewById<CardView>(R.id.search_results_container)
                if (adapter.count > 0) {
                    suggestionsContainer?.visibility = View.VISIBLE
                    suggestionsContainer?.elevation = 10f
                } else {
                    suggestionsContainer?.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching suggestions", e)
            }
        }
    }

    private fun isWithinNepalRadius(lat: Double, lng: Double): Boolean {
        // Nepal's approximate bounding box
        val minLat = 26.0
        val maxLat = 31.0
        val minLng = 80.0
        val maxLng = 89.0
        
        return lat in minLat..maxLat && lng in minLng..maxLng
    }

    private fun performSearch(query: String) {
        coroutineScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    val geocoder = android.location.Geocoder(requireContext())
                    geocoder.getFromLocationName(query, 5)
                }

                results?.firstOrNull()?.let { location ->
                    // Remove previous search markers
                    mapView.overlays.removeAll { it is Marker && it != currentMarker }

                    // Add new marker
                    val point = GeoPoint(location.latitude, location.longitude)
                    val marker = Marker(mapView).apply {
                        position = point
                        title = location.featureName
                        snippet = location.getAddressLine(0)
                    }
                    mapView.overlays.add(marker)

                    // Animate to the location
                    mapView.controller.animateTo(point)
                    mapView.controller.setZoom(15.0)
                    mapView.invalidate()
                } ?: run {
                    Toast.makeText(context, "No results found", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error searching location", Toast.LENGTH_SHORT).show()
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
        val fromText = fromLocation.text.toString()
        val toText = toLocation.text.toString()

        if (fromText.isEmpty() || toText.isEmpty()) {
            Toast.makeText(context, "Please enter both locations", Toast.LENGTH_SHORT).show()
            return
        }

        coroutineScope.launch {
            try {
                val geocoder = Geocoder(requireContext())
                
                // Get coordinates for both locations
                val fromResults = withContext(Dispatchers.IO) {
                    geocoder.getFromLocationName(fromText, 1)
                }
                val toResults = withContext(Dispatchers.IO) {
                    geocoder.getFromLocationName(toText, 1)
                }

                val fromPoint = fromResults?.firstOrNull()?.let { 
                    GeoPoint(it.latitude, it.longitude) 
                }
                val toPoint = toResults?.firstOrNull()?.let { 
                    GeoPoint(it.latitude, it.longitude) 
                }

                if (fromPoint != null && toPoint != null) {
                    // Clear existing route
                    currentRoute?.let { mapView.overlays.remove(it) }

                    // Use OSMBonusPack RoadManager
                    val roadManager = OSRMRoadManager(requireContext(), "OsmDroid")
                    
                    // Get the route
                    val waypoints = ArrayList<GeoPoint>()
                    waypoints.add(fromPoint)
                    waypoints.add(toPoint)
                    
                    val road = withContext(Dispatchers.IO) {
                        roadManager.getRoad(waypoints)
                    }

                    val routeLine = RoadManager.buildRoadOverlay(road)
                    routeLine.color = android.graphics.Color.BLUE
                    routeLine.width = 5f

                    // Add route to map
                    mapView.overlays.add(routeLine)
                    currentRoute = routeLine

                    // Add markers
                    mapView.overlays.add(Marker(mapView).apply {
                        position = fromPoint
                        title = "Start"
                    })
                    mapView.overlays.add(Marker(mapView).apply {
                        position = toPoint
                        title = "Destination"
                    })

                    // Zoom to show the entire route
                    val bounds = BoundingBox.fromGeoPoints(waypoints)
                    mapView.zoomToBoundingBox(bounds, true)
                    mapView.invalidate()

                    // Hide dialog
                    directionDialog.visibility = View.GONE
                } else {
                    Toast.makeText(context, "Could not find one or both locations", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error calculating route", e)
                Toast.makeText(context, "Error calculating route: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun decodePolyline(routeData: String): List<GeoPoint> {
        val points = mutableListOf<GeoPoint>()
        
        try {
            // Parse JSON response
            val jsonObject = JSONObject(routeData)
            val routes = jsonObject.getJSONArray("routes")
            
            if (routes.length() > 0) {
                val route = routes.getJSONObject(0)
                val geometry = route.getString("geometry")
                
                // Decode the polyline
                val decodedPath = PolylineEncoding.decode(geometry)
                
                // Convert to GeoPoints
                decodedPath.forEach { latLng ->
                    points.add(GeoPoint(latLng.latitude, latLng.longitude))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding polyline", e)
        }
        
        return points
    }

    // Add this utility class for polyline decoding
    private object PolylineEncoding {
        fun decode(encoded: String): List<LatLng> {
            val poly = mutableListOf<LatLng>()
            var index = 0
            var lat = 0
            var lng = 0

            while (index < encoded.length) {
                var shift = 0
                var result = 0
                
                // Decode latitude
                do {
                    result = result or ((encoded[index++].code - 63) and 0x1f shl shift)
                    shift += 5
                } while (encoded[index].code - 63 >= 0x20)
                
                lat += if (result and 1 != 0) -(result shr 1) else result shr 1

                // Decode longitude
                shift = 0
                result = 0
                do {
                    result = result or ((encoded[index++].code - 63) and 0x1f shl shift)
                    shift += 5
                } while (index < encoded.length && encoded[index].code - 63 >= 0x20)
                
                lng += if (result and 1 != 0) -(result shr 1) else result shr 1

                poly.add(LatLng(lat * 1e-5, lng * 1e-5))
            }

            return poly
        }
    }

    // Simple data class to hold latitude/longitude pairs
    private data class LatLng(val latitude: Double, val longitude: Double)

    private fun updateBusMarker(location: Location, isLoggedInBus: Boolean = true) {
        val latitude = location.latitude
        val longitude = location.longitude
        val currentPosition = GeoPoint(latitude, longitude)

        // Update or create marker
        if (currentMarker == null) {
            currentMarker = Marker(mapView).apply {
                title = "Bus Location"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                mapView.overlays.add(this)
            }
        }
        currentMarker?.position = currentPosition

        // Center map if tracking
        if (isLoggedInBus) {
            mapView.controller.animateTo(currentPosition)
        }

        mapView.invalidate()
    }
}