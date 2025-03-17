package com.example.newapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.newapp.databinding.ActivitySignupBinding
import com.example.newapp.utils.EncryptionUtil
import com.google.firebase.database.FirebaseDatabase
import java.util.UUID

class SignupActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySignupBinding
    private lateinit var database: FirebaseDatabase
    private val TAG = "SignupActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = FirebaseDatabase.getInstance()

        binding.signupButton.setOnClickListener {
            if (!validateInput()) return@setOnClickListener
            
            showLoading(true)
            val name = binding.nameEditText.text.toString()
            val email = binding.emailEditText.text.toString()
            val password = binding.passwordEditText.text.toString()
            
            createAccount(name, email, password)
        }

        binding.loginPrompt.setOnClickListener {
            finish()
        }
    }

    private fun validateInput(): Boolean {
        var isValid = true
        val name = binding.nameEditText.text.toString()
        val email = binding.emailEditText.text.toString()
        val password = binding.passwordEditText.text.toString()

        if (name.isEmpty()) {
            binding.nameLayout.error = "Name is required"
            isValid = false
        } else {
            binding.nameLayout.error = null
        }

        if (email.isEmpty()) {
            binding.emailLayout.error = "Email is required"
            isValid = false
        } else {
            binding.emailLayout.error = null
        }

        if (password.isEmpty()) {
            binding.passwordLayout.error = "Password is required"
            isValid = false
        } else if (password.length < 6) {
            binding.passwordLayout.error = "Password must be at least 6 characters"
            isValid = false
        } else {
            binding.passwordLayout.error = null
        }

        return isValid
    }

    private fun createAccount(name: String, email: String, password: String) {
        // Create a reference to driverInfo node
        val driverRef = database.reference.child("driverInfo")
        
        // Generate a unique key for the bus entry
        val busKey = driverRef.push().key ?: return
        
        // Create user data object matching the Firebase structure
        val userData = hashMapOf(
            "busID" to busKey,
            "email" to email,
            "password" to EncryptionUtil.encodeToBase64(password)
        )

        // Store in driverInfo node under the bus number
        driverRef.child(name).setValue(userData)  // 'name' will be the bus number (e.g., "bus1")
            .addOnSuccessListener {
                showLoading(false)
                Toast.makeText(this, "Account created successfully", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            .addOnFailureListener { e ->
                showLoading(false)
                Log.w(TAG, "Error adding user data", e)
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun showLoading(show: Boolean) {
        binding.signupButton.isEnabled = !show
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
    }
} 