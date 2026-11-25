package com.rembrowser.rembrowse.engine

import android.Manifest
import com.rembrowser.rembrowse.model.SitePermissionKind
import com.rembrowser.rembrowse.model.SitePermissionKind.CAMERA
import com.rembrowser.rembrowse.model.SitePermissionKind.LOCATION
import com.rembrowser.rembrowse.model.SitePermissionKind.MICROPHONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.MediaSource

/** The pure decisions inside the Gecko permission glue: what is asked, what is answered silently, which camera. */
class GeckoSitePermissionsMappingTest {

    private val companion = GeckoSitePermissions.Companion

    // --- Gecko type to kind ---

    @Test
    fun geckoTypesThatAskTheUserMapToTheirKind() {
        assertEquals(SitePermissionKind.LOCATION, GeckoSitePermissions.kindFor(PermissionDelegate.PERMISSION_GEOLOCATION))
        assertEquals(SitePermissionKind.NOTIFICATIONS, GeckoSitePermissions.kindFor(PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION))
        assertEquals(SitePermissionKind.PROTECTED_CONTENT, GeckoSitePermissions.kindFor(PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS))
        assertEquals(SitePermissionKind.STORAGE_ACCESS, GeckoSitePermissions.kindFor(PermissionDelegate.PERMISSION_STORAGE_ACCESS))
        assertEquals(SitePermissionKind.LOCAL_NETWORK, GeckoSitePermissions.kindFor(PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS))
        assertEquals(SitePermissionKind.LOCAL_DEVICE, GeckoSitePermissions.kindFor(PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS))
    }

    /** GeckoView's own name for content permission [type], as it reads and writes its store. */
    private fun geckoViewTypeName(type: Int): String {
        // Package-private in GeckoView (StorageController uses it); reached by reflection so the
        // names RemBrowse writes are checked against the engine's, not against a copy of them
        val method = ContentPermission::class.java.getDeclaredMethod(
            "convertType",
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(null, type, false) as String
    }

    @Test
    fun eachKindGeckoKeepsIsStoredUnderGeckoViewsOwnTypeName() {
        val types = mapOf(
            SitePermissionKind.LOCATION to PermissionDelegate.PERMISSION_GEOLOCATION,
            SitePermissionKind.NOTIFICATIONS to PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION,
            SitePermissionKind.PROTECTED_CONTENT to PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS,
            SitePermissionKind.LOCAL_NETWORK to PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS,
            SitePermissionKind.LOCAL_DEVICE to PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS
        )
        types.forEach { (kind, type) ->
            val name = GeckoSitePermissions.geckoTypeName(kind)
            assertEquals("type name for $kind", geckoViewTypeName(type), name)
            // And a row of that type reads back as the same kind
            assertEquals(kind to null, GeckoSitePermissions.kindOfType(name!!))
        }
        assertEquals("local-network", GeckoSitePermissions.geckoTypeName(SitePermissionKind.LOCAL_NETWORK))
        assertEquals("loopback-network", GeckoSitePermissions.geckoTypeName(SitePermissionKind.LOCAL_DEVICE))
    }

    @Test
    fun kindsGeckoDoesNotKeepByOneNameHaveNoTypeName() {
        // Camera and microphone are RemBrowse's own records; storage access has a key per embedded site
        listOf(CAMERA, MICROPHONE, SitePermissionKind.STORAGE_ACCESS).forEach {
            assertNull("type name for $it", GeckoSitePermissions.geckoTypeName(it))
        }
    }

    @Test
    fun geckoTypesThatNeverAskTheUserHaveNoKind() {
        listOf(
            PermissionDelegate.PERMISSION_PERSISTENT_STORAGE,
            PermissionDelegate.PERMISSION_XR,
            PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
            PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
            PermissionDelegate.PERMISSION_TRACKING,
            99, -1
        ).forEach { assertNull("kind for type $it", GeckoSitePermissions.kindFor(it)) }
    }

    // --- silent answers ---

    @Test
    fun harmlessPermissionsAreAllowedWithoutAsking() {
        assertEquals(ContentPermission.VALUE_ALLOW, GeckoSitePermissions.silentAnswer(PermissionDelegate.PERMISSION_PERSISTENT_STORAGE))
        assertEquals(ContentPermission.VALUE_ALLOW, GeckoSitePermissions.silentAnswer(PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE))
    }

    @Test
    fun soundThatStartsByItselfTrackingXrAndUnknownTypesAreRefusedWithoutAsking() {
        listOf(
            PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
            PermissionDelegate.PERMISSION_XR,
            PermissionDelegate.PERMISSION_TRACKING,
            99
        ).forEach {
            assertEquals("answer for type $it", ContentPermission.VALUE_DENY, GeckoSitePermissions.silentAnswer(it))
        }
    }

    // --- Android permission names ---

    @Test
    fun androidPermissionsMapBackToTheirKind() {
        assertEquals(CAMERA, GeckoSitePermissions.kindForAndroidPermission(Manifest.permission.CAMERA))
        assertEquals(MICROPHONE, GeckoSitePermissions.kindForAndroidPermission(Manifest.permission.RECORD_AUDIO))
        assertEquals(LOCATION, GeckoSitePermissions.kindForAndroidPermission(Manifest.permission.ACCESS_FINE_LOCATION))
        assertEquals(LOCATION, GeckoSitePermissions.kindForAndroidPermission(Manifest.permission.ACCESS_COARSE_LOCATION))
        assertNull(GeckoSitePermissions.kindForAndroidPermission(Manifest.permission.READ_CONTACTS))
        assertNull(GeckoSitePermissions.kindForAndroidPermission(Manifest.permission.POST_NOTIFICATIONS))
    }

    @Suppress("UNCHECKED_CAST")
    private fun androidPermissionsFor(kinds: Set<SitePermissionKind>): List<String> {
        // Private in the companion; reached by reflection so the camera and microphone names stay checked
        val method = GeckoSitePermissions.Companion::class.java.getDeclaredMethod("androidPermissionsFor", Set::class.java)
        method.isAccessible = true
        return method.invoke(companion, kinds) as List<String>
    }

    @Test
    fun cameraAndMicrophoneNeedTheirOwnAndroidPermissionsAndNothingElseDoes() {
        assertEquals(listOf(Manifest.permission.CAMERA), androidPermissionsFor(setOf(CAMERA)))
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), androidPermissionsFor(setOf(MICROPHONE)))
        assertEquals(
            setOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
            androidPermissionsFor(setOf(CAMERA, MICROPHONE)).toSet()
        )
        assertTrue(androidPermissionsFor(setOf(SitePermissionKind.NOTIFICATIONS, SitePermissionKind.STORAGE_ACCESS)).isEmpty())
        // The local network kinds need no Android permission of RemBrowse's asking (see onAndroidRequest)
        assertTrue(androidPermissionsFor(setOf(SitePermissionKind.LOCAL_NETWORK, SitePermissionKind.LOCAL_DEVICE)).isEmpty())
    }

    @Test
    fun theLocalNetworkPermissionIsNotAskedForWhileTheAppTargetsLessThanApi37() {
        val lan = "android.permission.ACCESS_LOCAL_NETWORK"
        val camera = Manifest.permission.CAMERA
        assertEquals(lan, GeckoSitePermissions.ACCESS_LOCAL_NETWORK)
        // Held through INTERNET: nothing is left to ask Android, so the request is granted as it is
        assertTrue(GeckoSitePermissions.androidPermissionsToAsk(listOf(lan), targetSdk = 36).isEmpty())
        assertTrue(GeckoSitePermissions.androidPermissionsToAsk(listOf(lan, lan), targetSdk = 35).isEmpty())
        // What comes with it is still asked for, once
        assertEquals(listOf(camera), GeckoSitePermissions.androidPermissionsToAsk(listOf(lan, camera, camera), targetSdk = 36))
        // From 37 the app has to ask
        assertEquals(listOf(lan), GeckoSitePermissions.androidPermissionsToAsk(listOf(lan), targetSdk = 37))
        assertEquals(listOf(camera, lan), GeckoSitePermissions.androidPermissionsToAsk(listOf(camera, lan), targetSdk = 38))
        assertTrue(GeckoSitePermissions.androidPermissionsToAsk(emptyList(), targetSdk = 36).isEmpty())
    }

    @Test
    fun missingCameraOrMicrophoneIsNotMadeUpForByLocation() {
        val camera = Manifest.permission.CAMERA
        val mic = Manifest.permission.RECORD_AUDIO
        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        assertFalse(GeckoSitePermissions.holdsEnough(listOf(camera, mic), setOf(camera)))
        assertFalse(GeckoSitePermissions.holdsEnough(listOf(camera), setOf(fine)))
        assertTrue(GeckoSitePermissions.holdsEnough(listOf(camera, mic), setOf(camera, mic, fine)))
        assertTrue(GeckoSitePermissions.holdsEnough(emptyList(), emptySet()))
    }

    @Test
    fun anythingNeedingLocationAcceptsOnlyALocationPermissionAndNoLocationNeedAcceptsNone() {
        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
        assertFalse(GeckoSitePermissions.holdsEnough(listOf(fine), emptySet()))
        assertFalse(GeckoSitePermissions.holdsEnough(listOf(fine, coarse), setOf(Manifest.permission.CAMERA)))
        assertTrue(GeckoSitePermissions.holdsEnough(listOf(fine), setOf(coarse)))
    }

    // --- camera and microphone choice ---

    private fun source(kind: Int, name: String?, id: String = name.orEmpty()): MediaSource {
        val source = object : MediaSource() {}
        fun set(field: String, value: Any?) {
            val f = MediaSource::class.java.getDeclaredField(field)
            f.isAccessible = true
            f.set(source, value)
        }
        set("id", id)
        set("name", name)
        set("source", kind)
        return source
    }

    @Test
    fun theFrontCameraIsPickedWhereItsNameTellsAndOtherwiseTheFirst() {
        val back = source(MediaSource.SOURCE_CAMERA, "Camera 0, Facing back")
        val front = source(MediaSource.SOURCE_CAMERA, "Camera 1, Facing FRONT")
        val mic = source(MediaSource.SOURCE_MICROPHONE, "Mic")

        assertSame(front, GeckoSitePermissions.pickCamera(arrayOf(back, mic, front)))
        assertSame(back, GeckoSitePermissions.pickCamera(arrayOf(back, mic, source(MediaSource.SOURCE_CAMERA, "Camera 2"))))
    }

    @Test
    fun noCameraGivesNullAndAMicrophoneIsNeverPickedAsACamera() {
        assertNull(GeckoSitePermissions.pickCamera(emptyArray()))
        assertNull(GeckoSitePermissions.pickCamera(arrayOf(source(MediaSource.SOURCE_MICROPHONE, "front mic"))))
        assertNull(GeckoSitePermissions.pickCamera(arrayOf(source(MediaSource.SOURCE_SCREEN, "front screen"))))
    }

    @Test
    fun aCameraWithoutANameIsStillUsable() {
        val unnamed = source(MediaSource.SOURCE_CAMERA, null, id = "cam")
        assertSame(unnamed, GeckoSitePermissions.pickCamera(arrayOf(unnamed)))
    }

    @Test
    fun theFirstMicrophoneIsPickedAndCamerasAreSkipped() {
        val cam = source(MediaSource.SOURCE_CAMERA, "Camera")
        val mic1 = source(MediaSource.SOURCE_MICROPHONE, "Mic 1")
        val mic2 = source(MediaSource.SOURCE_MICROPHONE, "Mic 2")
        assertSame(mic1, GeckoSitePermissions.pickMicrophone(arrayOf(cam, mic1, mic2)))
        assertNull(GeckoSitePermissions.pickMicrophone(arrayOf(cam)))
        assertNull(GeckoSitePermissions.pickMicrophone(emptyArray()))
    }
}
