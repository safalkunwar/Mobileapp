package com.example.newapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.newapp.databinding.ActivityLoginBinding
import com.example.newapp.utils.EncryptionUtil
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.DatabaseReference
import android.os.Handler
import android.os.Looper

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var database: FirebaseDatabase
    private val TAG = "LoginActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Firebase
        database = Firebase.database

        binding.loginButton.setOnClickListener {
            if (!validateInput()) return@setOnClickListener
            
            val busId = binding.busIdInput.text.toString()
            val password = binding.passwordEditText.text.toString()
            
            if (busId.isNotEmpty() && password.isNotEmpty()) {
                showLoading(true)
                signIn(busId, password)
            }
        }

        binding.signupPrompt.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }

        binding.forgotPasswordText.setOnClickListener {
            val busId = binding.busIdInput.text.toString()
            if (busId.isEmpty()) {
                binding.busIdLayout.error = "Enter Bus ID to reset password"
                return@setOnClickListener
            }
            
            // For this example, we'll just show a message
            Toast.makeText(this, 
                "Please contact administrator to reset password", 
                Toast.LENGTH_LONG).show()
        }
    }

    private fun signIn(busId: String, password: String) {
        Log.d(TAG, "Attempting to sign in with Bus ID: $busId")
        
        val driverRef = database.reference.child("driverInfo")
        showLoading(true)

        // Query the specific bus node directly
        driverRef.child(busId).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                Log.d(TAG, "Firebase response received. Data exists: ${snapshot.exists()}")

                if (snapshot.exists()) {
                    try {
                        val storedPassword = snapshot.child("password").getValue(String::class.java)
                        Log.d(TAG, "Found user data, checking password")
                        
                        if (storedPassword != null) {
                            // Compare encoded passwords
                            val encodedInputPassword = EncryptionUtil.encodeToBase64(password)
                            if (storedPassword == encodedInputPassword) {
                                Log.d(TAG, "Password match found")
                                
                                // Save bus ID to preferences
                                getSharedPreferences("MyPrefs", MODE_PRIVATE).edit()
                                    .putString("driverId", busId)
                                    .apply()

                                Log.d(TAG, "Saved driver ID to preferences: $busId")

                                showLoading(false)
                                startMainActivity()
                                return
                            }
                        }
                        
                        // If we get here, password didn't match
                        showLoading(false)
                        Toast.makeText(this@LoginActivity, 
                            "Invalid password", 
                            Toast.LENGTH_LONG).show()
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing user data", e)
                        showLoading(false)
                        Toast.makeText(this@LoginActivity, 
                            "Error processing login", 
                            Toast.LENGTH_LONG).show()
                    }
                } else {
                    showLoading(false)
                    Toast.makeText(this@LoginActivity, 
                        "Bus ID not found", 
                        Toast.LENGTH_LONG).show()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                showLoading(false)
                Log.e(TAG, "Login cancelled", error.toException())
                Toast.makeText(this@LoginActivity, 
                    "Login failed: ${error.message}", 
                    Toast.LENGTH_LONG).show()
            }
        })

        // Add a timeout handler
        Handler(Looper.getMainLooper()).postDelayed({
            if (binding.progressBar.visibility == View.VISIBLE) {
                showLoading(false)
                Toast.makeText(this, 
                    "Login timeout. Please try again.", 
                    Toast.LENGTH_LONG).show()
            }
        }, 10000) // 10 second timeout
    }

    private fun validateInput(): Boolean {
        var isValid = true
        val busId = binding.busIdInput.text.toString()
        val password = binding.passwordEditText.text.toString()

        if (busId.isEmpty()) {
            binding.busIdLayout.error = "Bus ID is required"
            isValid = false
        }

        if (password.isEmpty()) {
            binding.passwordLayout.error = "Password is required"
            isValid = false
        }

        return isValid
    }

    private fun showLoading(show: Boolean) {
        binding.loginButton.isEnabled = !show
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun startMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
} 