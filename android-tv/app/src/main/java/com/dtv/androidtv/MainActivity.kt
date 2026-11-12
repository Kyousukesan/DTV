package com.dtv.androidtv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import com.dtv.androidtv.ui.DtvTvNav
import com.dtv.androidtv.ui.theme.DtvTvTheme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DtvTvTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DtvTvNav(catalog = DtvTvApp.instance.platforms)
                }
            }
        }
    }
}
