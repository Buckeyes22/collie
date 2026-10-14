package com.lateapex.collie.ui

import android.content.Context
import android.content.SharedPreferences
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.data.custodyOwner
import com.lateapex.collie.network.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** Restores a selected face after restart/navigation using a fresh admitted config and read actor. */
internal class OperatorTypefaceRestorer(context: Context, private val repository: CollieRepository) {
    private val context = context.applicationContext
    private val preferences = NativePreferences(this.context)
    private val signals = MutableStateFlow(0L)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in setOf(NativePreferences.UI_TYPEFACE, NativePreferences.OPERATOR_TYPEFACE_FAMILY,
            NativePreferences.OPERATOR_TYPEFACE_BASENAME, NativePreferences.OPERATOR_TYPEFACE_OWNER)) signals.value++
    }
    private data class Restore(val actor: ScopedReadContext?, val selection: NativePreferences.OperatorTypefaceSelection?, val epoch: Long) {
        override fun equals(other: Any?): Boolean = other is Restore && actor?.key == other.actor?.key &&
            actor?.navigationGeneration == other.actor?.navigationGeneration && selection == other.selection && epoch == other.epoch
        override fun hashCode(): Int = 31 * (actor?.key?.hashCode() ?: 0) + (selection?.hashCode() ?: 0)
    }
    fun start() {
        PreferredTypeface.observe(repository)
        context.getSharedPreferences(NativePreferences.PREFERENCES, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(listener)
        scope.launch {
            combine(repository.connection, repository.selectedScope, repository.readSource, signals) { _, _, _, _ -> Unit }
                .map { Restore(repository.readContext(), preferences.operatorTypefaceSelection, selectionEpoch.get()) }
                .distinctUntilChanged().collectLatest { request ->
                    val actor = request.actor ?: return@collectLatest
                    val selected = request.selection ?: return@collectLatest
                    val current = { selectionEpoch.get() == request.epoch && preferences.operatorTypefaceSelection == selected && repository.isCurrent(actor) }
                    if (!current() || selected.ownerFingerprint != actor.custodyOwner.storageIdentity) return@collectLatest
                    val config = repository.config(actor.key.scope, actor)
                    if (!current()) return@collectLatest
                    val row = (config as? ApiResult.Success)?.value?.operatorFonts?.firstOrNull {
                        it.valid() && it.family == selected.family && it.basename == selected.basename
                    }
                    if (row == null) { PreferredTypeface.clearLoadedOperator(); return@collectLatest }
                    OperatorTypefaceLoader(context, repository).select(row, actor, current)
                }
        }
    }
    companion object {
        private val selectionEpoch = AtomicLong(0)
        /** Every explicit choice supersedes an in-flight automatic restoration. */
        fun supersede() { selectionEpoch.incrementAndGet() }
    }
}
