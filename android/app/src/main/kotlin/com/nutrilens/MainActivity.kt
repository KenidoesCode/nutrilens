package com.nutrilens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutrilens.core.designsystem.theme.NutriLensTheme
import com.nutrilens.navigation.NutriLensApp
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single activity hosting the Compose interface.
 *
 * One activity, many composable destinations: navigation state is one graph
 * rather than a stack of activities, which is what lets the app restore
 * exactly where the user left off after process death.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            NutriLensTheme {
                NutriLensApp(startDestination = uiState.startDestination)
            }
        }
    }
}
