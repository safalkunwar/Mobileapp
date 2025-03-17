package com.example.newapp

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

class FirebaseManager : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        
        // Enable offline persistence
        Firebase.database.setPersistenceEnabled(true)
    }
} 