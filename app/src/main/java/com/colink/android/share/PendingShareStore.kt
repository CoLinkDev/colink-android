package com.colink.android.share

import android.net.Uri
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class PendingShareStore @Inject constructor() {
    private val pending = AtomicReference<PendingShare?>(null)
    private val _share = MutableStateFlow<PendingShare?>(null)
    val share: StateFlow<PendingShare?> = _share.asStateFlow()

    fun set(share: PendingShare) {
        pending.set(share)
        _share.value = share
    }

    fun consume(): PendingShare? {
        val value = pending.getAndSet(null)
        _share.value = null
        return value
    }
}

sealed interface PendingShare {
    data class Text(val text: String) : PendingShare
    data class File(val uri: Uri) : PendingShare
}
