package com.example.newapp.ui.home

import androidx.lifecycle.ViewModel

class HomeViewModel : ViewModel() {
    val userName: String = "John Doe" // This could come from user preferences or backend

    fun getNotice(): String {
        // This could fetch from a backend service
        return "Welcome to our app! Check out the latest updates and photos."
    }
}