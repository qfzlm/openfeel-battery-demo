package com.example.twsbatterydemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.twsbatterydemo.ui.MainScreen
import com.example.twsbatterydemo.ui.MainViewModel

class MainActivity : ComponentActivity() {

    private val container by lazy { AppContainer(this) }

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory(
            bleScannerManager = container.bleScannerManager
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            viewModel.refreshEnvironment()
        }

        setContent {
            MaterialTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                MainScreen(
                    state = state,
                    onRefreshBattery = {
                        val missing = state.missingPermissions.toTypedArray()
                        if (missing.isNotEmpty()) {
                            permissionLauncher.launch(missing)
                        } else {
                            viewModel.refreshBatteryNow()
                        }
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.refreshEnvironment()
    }

    override fun onStop() {
        super.onStop()
        viewModel.stopScan()
    }
}
