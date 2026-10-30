package com.lianwenqi.app.util

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 音量键翻页映射验收（产品口径）：
 * 音量加键（KEYCODE_VOLUME_UP）→ 上一页（-1），音量减键（KEYCODE_VOLUME_DOWN）→ 下一页（+1）。
 * 断言直接使用 Android 的物理键码，不依赖内部枚举名或「向前 / 向后」等口语描述。
 */
@RunWith(AndroidJUnit4::class)
class VolumeKeyMappingTest {

    @Test
    fun 音量加键上一页音量减键下一页() {
        assertEquals(-1, volumeKeyNavDirection(KeyEvent.KEYCODE_VOLUME_UP)!!)
        assertEquals(1, volumeKeyNavDirection(KeyEvent.KEYCODE_VOLUME_DOWN)!!)
    }

    @Test
    fun 非音量键不触发翻页() {
        assertNull(volumeKeyNavDirection(KeyEvent.KEYCODE_BACK))
        assertNull(volumeKeyNavDirection(KeyEvent.KEYCODE_POWER))
        assertNull(volumeKeyNavDirection(KeyEvent.KEYCODE_A))
    }
}
