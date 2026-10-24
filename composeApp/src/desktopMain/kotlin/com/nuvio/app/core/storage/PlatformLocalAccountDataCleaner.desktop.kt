package com.nuvio.app.core.storage

import com.nuvio.app.features.livetv.LiveTvRepository

internal actual object PlatformLocalAccountDataCleaner {
    actual fun wipe() {
        LiveTvRepository.clearAll()
        DesktopStorage.wipe()
    }
}
