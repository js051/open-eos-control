// Synthetic violations. Only the invocation-scoped Lint canary loads this file.
package dev.openeos.lintcanary

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.MutableLiveData

@Composable
fun FoundationCanary() {
    BoxWithConstraints { Box(Modifier) }
}

@Composable
fun RuntimeCanary() {
    mutableStateOf("synthetic")
}

fun uiCanary(): Modifier = Modifier

fun liveDataCanary() {
    val state = MutableLiveData<String>()
    state.value = null
}
