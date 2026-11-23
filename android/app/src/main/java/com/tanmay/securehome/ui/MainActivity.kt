package com.tanmay.securehome.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    /** Bumped on every resume so screens re-check permissions the user may have changed in Settings. */
    private val resumes = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SecureHomeTheme {
                App(
                    vm = vm,
                    resumes = resumes.intValue,
                    hasSms = {
                        listOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
                            .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
                    },
                    openAppSettings = ::openAppSettings,
                    scan = ::scanQr,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumes.intValue++
        vm.refresh()
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    /** Google's code scanner: no camera permission needed, the scan UI runs in Play services. */
    private fun scanQr() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { vm.scanned(it.rawValue.orEmpty()) }
            .addOnFailureListener { vm.message = "Couldn't open the scanner. Paste the pairing text instead." }
    }
}
