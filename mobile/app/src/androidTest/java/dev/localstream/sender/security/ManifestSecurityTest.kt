@file:Suppress("DEPRECATION")

package dev.localstream.sender.security

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.localstream.sender.MainActivity
import dev.localstream.sender.pairing.PairingActivity
import dev.localstream.sender.service.StreamingService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManifestSecurityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun declaresOnlyRequiredSensitivePermissionsAndNoBroadStorageOrLocation() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val permissions = info.requestedPermissions.orEmpty().toSet()

        assertTrue(Manifest.permission.CAMERA in permissions)
        assertTrue(Manifest.permission.RECORD_AUDIO in permissions)
        assertTrue(Manifest.permission.INTERNET in permissions)
        assertFalse(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
        assertFalse(Manifest.permission.ACCESS_COARSE_LOCATION in permissions)
        assertFalse("android.permission.READ_MEDIA_IMAGES" in permissions)
        assertFalse("android.permission.MANAGE_EXTERNAL_STORAGE" in permissions)
    }

    @Test
    fun onlyLauncherActivityIsExportedAndBackupsAreDisabled() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES,
        )
        val components = packageInfo.activities.orEmpty().associateBy { it.name }
        val services = packageInfo.services.orEmpty().associateBy { it.name }

        assertTrue(components.getValue(MainActivity::class.java.name).exported)
        assertFalse(components.getValue(PairingActivity::class.java.name).exported)
        assertFalse(services.getValue(StreamingService::class.java.name).exported)
        assertFalse(context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
    }
}
