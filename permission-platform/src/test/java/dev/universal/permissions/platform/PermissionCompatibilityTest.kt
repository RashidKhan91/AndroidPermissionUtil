package dev.universal.permissions.platform

import dev.universal.permissions.model.PermissionType
import org.junit.Assert.*
import org.junit.Test

class PermissionCompatibilityTest {
    @Test fun mediaPermissionsChangeAtApi33() {
        assertEquals("android.permission.READ_EXTERNAL_STORAGE", PermissionCompatibility.runtimePermissions(PermissionType.READ_MEDIA_IMAGES, 32)?.single())
        assertEquals("android.permission.READ_MEDIA_IMAGES", PermissionCompatibility.runtimePermissions(PermissionType.READ_MEDIA_IMAGES, 33)?.single())
    }
    @Test fun selectedPhotosStartsAtApi34() {
        assertNull(PermissionCompatibility.runtimePermissions(PermissionType.SELECTED_PHOTOS, 33))
        assertEquals("android.permission.READ_MEDIA_VISUAL_USER_SELECTED", PermissionCompatibility.runtimePermissions(PermissionType.SELECTED_PHOTOS, 34)?.single())
    }
    @Test fun bluetoothUsesLocationBeforeApi31() {
        assertEquals("android.permission.ACCESS_FINE_LOCATION", PermissionCompatibility.runtimePermissions(PermissionType.BLUETOOTH_SCAN, 30)?.single())
        assertEquals("android.permission.BLUETOOTH_SCAN", PermissionCompatibility.runtimePermissions(PermissionType.BLUETOOTH_SCAN, 31)?.single())
    }
    @Test fun api34ImagesRequestFullAndSelectedAccessTogether() {
        assertEquals(listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"),
            PermissionCompatibility.runtimePermissions(PermissionType.READ_MEDIA_IMAGES, 34))
    }
    @Test fun preciseLocationRequestsApproximateAndPreciseTogetherOnApi31() {
        assertEquals(listOf("android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION"),
            PermissionCompatibility.runtimePermissions(PermissionType.ACCESS_FINE_LOCATION, 31))
    }
}
