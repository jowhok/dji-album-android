package com.wei.djialbum

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.wei.djialbum.ui.AlbumViewModel
import com.wei.djialbum.ui.AppRoot
import com.wei.djialbum.ui.theme.DjiTheme

class MainActivity : ComponentActivity() {

    private val vm: AlbumViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val mode by vm.themeMode.collectAsState()
            DjiTheme(mode) {
                AppRoot(vm)
            }
        }
    }
}
