package com.example.twsbatterydemo

import android.content.Context
import com.example.twsbatterydemo.ble.BleScannerManager

class AppContainer(context: Context) {

    val bleScannerManager: BleScannerManager = BleScannerManager(
        context = context.applicationContext
    )
}
