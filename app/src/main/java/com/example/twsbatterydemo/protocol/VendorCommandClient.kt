package com.example.twsbatterydemo.protocol

interface VendorCommandClient {
    fun requestBatteryInfo(macAddress: String)
}
