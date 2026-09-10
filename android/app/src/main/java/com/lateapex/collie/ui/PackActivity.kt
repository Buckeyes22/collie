package com.lateapex.collie.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.PackMember
import com.lateapex.collie.network.PackStatusResponse
import com.lateapex.collie.network.SnapshotResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Read-only native `/pack`, presented with the same formation and paperwork flow as the PWA. */
class PackActivity : AppCompatActivity() {
    private val repository get() = (application as CollieApplication).container.repository
    private val nativePreferences by lazy { NativePreferences(this) }
    private lateinit var summary: TextView
    private lateinit var formation: PackFormationView
    private lateinit var emptyCard: LinearLayout
    private lateinit var emptyTitle: TextView
    private lateinit var emptyDescription: TextView
    private var pollJob: Job? = null
    private var status: PackStatusResponse? = null
    private var selectedMemberId: String? = null
    private var memberDialog: CollieBottomSheetDialog? = null
    private var memberDialogBody: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.prepareEdgeToEdgeContent()

        val root = FrameLayout(this).apply { setBackgroundColor(color(R.color.collie_background)) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.collie_background))
        }
        root.addView(
            column,
            FrameLayout.LayoutParams(
                resources.displayMetrics.density.times(640).toInt().coerceAtMost(resources.displayMetrics.widthPixels),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER_HORIZONTAL,
            ),
        )
        column.addView(header(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val content = LinearLayout(this).apply {
            id = R.id.pack_members
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        formation = PackFormationView(this).apply { id = R.id.pack_formation }
        summary = TextView(this).apply {
            id = R.id.pack_summary
            setText(R.string.pack_loading)
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.collie_muted))
            setPadding(0, dp(12), 0, 0)
        }
        emptyCard = emptyCard().also { it.isVisible = false }
        content.addView(formation, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(summary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(emptyCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(content)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)
        window.applySafeContentInsets(root)
        root.applyAppTypeface()
    }

    override fun onStart() {
        super.onStart()
        if (pollJob != null) return
        pollJob = lifecycleScope.launch {
            while (isActive) {
                load()
                delay(POLL_MS)
            }
        }
    }

    override fun onStop() {
        pollJob?.cancel()
        pollJob = null
        super.onStop()
    }

    private suspend fun load() = coroutineScope {
        val packRequest = async { repository.pack() }
        val snapshotRequest = async { repository.snapshot() }
        when (val result = packRequest.await()) {
            is ApiResult.Success -> render(result.value, (snapshotRequest.await() as? ApiResult.Success)?.value)
            is ApiResult.Failure -> {
                snapshotRequest.cancel()
                renderEmpty(result.error is ApiFailure.Http && result.error.status == 404)
            }
            is ApiResult.NotModified -> snapshotRequest.cancel()
        }
    }

    private fun render(value: PackStatusResponse, snapshot: SnapshotResponse?) {
        status = value
        emptyCard.isVisible = false
        formation.isVisible = true
        summary.isVisible = true
        val leadId = value.members.firstOrNull(PackMember::isLead)?.id.orEmpty()
        val blocked = snapshot?.agents.orEmpty()
            .filter { it.status == AgentStatus.BLOCKED }
            .groupingBy { pane -> pane.host ?: leadId }
            .eachCount()
        val servers = snapshot?.servers.orEmpty().associateBy { it.id }
        val hostStates = value.members.mapNotNull { member ->
            val server = servers[member.id] ?: return@mapNotNull null
            member.id to DashboardHostHealthModel.from(
                server,
                at = snapshot?.ts ?: value.ts,
                pollMs = POLL_MS,
            ).state
        }.toMap()
        formation.submit(value.members, value.deputy?.id, blocked, hostStates, ::showMember)
        val reachable = value.members.count { it.health == "reachable" }
        summary.text = getString(
            R.string.pack_formation_summary,
            value.pack.name.ifBlank { value.pack.id },
            resources.getQuantityString(R.plurals.pack_machines, value.members.size, value.members.size),
            reachable,
        )
        selectedMemberId?.let { selectedId ->
            val selected = value.members.firstOrNull { it.id == selectedId }
            if (selected == null) memberDialog?.dismiss() else refreshMemberSheet(selected)
        }
    }

    private fun renderEmpty(solo: Boolean) {
        status = null
        formation.isVisible = false
        summary.isVisible = false
        emptyCard.isVisible = true
        emptyTitle.setText(if (solo) R.string.pack_solo_title else R.string.pack_error_title)
        emptyDescription.setText(if (solo) R.string.pack_solo_description else R.string.pack_error_description)
        memberDialog?.dismiss()
    }

    private fun header(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(60)
        setPadding(dp(16), 0, dp(16), 0)
        setBackgroundResource(R.drawable.bg_pane_header)
        addView(android.widget.ImageButton(this@PackActivity).apply {
            id = R.id.pack_back_button
            setImageResource(R.drawable.ic_collie_back)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            contentDescription = getString(R.string.pack_back)
            setOnClickListener {
                startActivity(Intent(this@PackActivity, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ))
                finish()
            }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(CollieHeadingTextView(this@PackActivity).apply {
            setText(R.string.pack_title)
            textSize = 18f
            setTextColor(color(R.color.collie_foreground))
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun emptyCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setBackgroundResource(R.drawable.bg_pane_card)
        addView(AppCompatImageView(this@PackActivity).apply {
            setImageResource(R.drawable.ic_pack_network)
            imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.collie_muted))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply {
            topMargin = dp(2)
            marginEnd = dp(12)
        })
        addView(LinearLayout(this@PackActivity).apply {
            orientation = LinearLayout.VERTICAL
            emptyTitle = CollieHeadingTextView(this@PackActivity).apply {
                textSize = 16f
                setTextColor(color(R.color.collie_foreground))
            }
            emptyDescription = TextView(this@PackActivity).apply {
                textSize = 14f
                setTextColor(color(R.color.collie_muted))
                setPadding(0, dp(4), 0, 0)
            }
            addView(emptyTitle)
            addView(emptyDescription)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun showMember(member: PackMember) {
        selectedMemberId = member.id
        val dialog = memberDialog ?: CollieBottomSheetDialog(this, member.name.ifBlank { member.id }).also { sheet ->
            memberDialogBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            sheet.setSheetContent(memberDialogBody!!)
            sheet.setOnDismissListener {
                selectedMemberId = null
                memberDialog = null
                memberDialogBody = null
            }
            memberDialog = sheet
            sheet
        }
        refreshMemberSheet(member)
        if (!dialog.isShowing) dialog.show()
    }

    private fun refreshMemberSheet(member: PackMember) {
        val currentStatus = status ?: return
        memberDialogBody?.apply {
            removeAllViews()
            val isDeputy = currentStatus.deputy?.id == member.id
            if (member.isLead || isDeputy) {
                addView(TextView(this@PackActivity).apply {
                    text = if (member.isLead) getString(R.string.pack_role_lead) else getString(R.string.pack_role_deputy)
                    textSize = 10f
                    setTextColor(color(R.color.collie_muted))
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    setBackgroundColor(color(R.color.collie_muted_surface))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
            }
            val facts = LinearLayout(this@PackActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_pane_card)
            }
            facts.addView(factRow(getString(R.string.pack_member_health), healthLabel(member.health), healthColor(member.health)))
            member.reason?.takeIf(String::isNotBlank)?.let {
                facts.addView(factRow(getString(R.string.pack_member_reason), it, color(R.color.collie_foreground), true))
            }
            member.conflict?.let { conflict ->
                val detail = if (conflict.warrantGeneration == null) {
                    getString(R.string.pack_conflict_no_warrant, conflict.leadMemberId)
                } else {
                    getString(R.string.pack_conflict_value, conflict.leadMemberId, conflict.warrantGeneration)
                }
                facts.addView(factRow(getString(R.string.pack_member_conflict), detail, color(R.color.collie_blocked), true))
            }
            member.version?.let { version ->
                val detail = if (version == currentStatus.self.version) version else getString(R.string.pack_version_differs, version)
                facts.addView(factRow(getString(R.string.pack_member_version), detail, if (version == currentStatus.self.version) color(R.color.collie_foreground) else color(R.color.collie_blocked)))
            }
            member.address?.let { facts.addView(factRow(getString(R.string.pack_member_address), it, color(R.color.collie_foreground), true)) }
            member.enrolledAt?.let { facts.addView(factRow(getString(R.string.pack_member_enrolled), timeAgo(it, currentStatus.ts))) }
            if (member.isLead) {
                val deputy = currentStatus.deputy
                val deputyName = deputy?.let { named -> currentStatus.members.firstOrNull { it.id == named.id }?.name?.ifBlank { named.id } ?: named.id }
                val deputyDetail = if (deputy == null || deputyName == null) {
                    getString(R.string.pack_no_deputy)
                } else if (deputy.warrantGeneration == null) {
                    deputyName
                } else {
                    getString(R.string.pack_deputy_warrant, deputyName, deputy.warrantGeneration)
                }
                facts.addView(factRow(getString(R.string.pack_summary_deputy), deputyDetail))
                facts.addView(factRow(
                    getString(R.string.pack_summary_secret),
                    getString(R.string.pack_secret_value, currentStatus.pack.secretGeneration, timeAgo(currentStatus.pack.rotatedAt, currentStatus.ts)),
                ))
            }
            addView(facts)
            if (member.secretBehind || member.provisional) {
                addView(TextView(this@PackActivity).apply {
                    text = listOfNotNull(
                        getString(R.string.pack_member_secret_behind).takeIf { member.secretBehind },
                        getString(R.string.pack_member_provisional).takeIf { member.provisional },
                    ).joinToString("\n")
                    textSize = 12f
                    setTextColor(color(R.color.collie_blocked))
                    setPadding(0, dp(12), 0, 0)
                })
            }
            addView(MaterialButton(this@PackActivity).apply {
                setText(R.string.pack_go_to_machine)
                setOnClickListener { goTo(member) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(12) })
            applyAppTypeface()
        }
    }

    private fun factRow(label: String, value: String, valueColor: Int = color(R.color.collie_foreground), mono: Boolean = false): View =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(TextView(this@PackActivity).apply {
                text = label
                textSize = 14f
                setTextColor(color(R.color.collie_muted))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(16) })
            addView(TextView(this@PackActivity).apply {
                text = value
                textSize = if (mono) 11f else 14f
                gravity = Gravity.END
                setTextColor(valueColor)
                if (mono) typeface = Typeface.MONOSPACE
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun goTo(member: PackMember) {
        repository.selectScope(Scope(host = if (member.isLead) null else member.id))
        memberDialog?.dismiss()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun timeAgo(then: Long, now: Long): String = DateUtils.getRelativeTimeSpanString(
        then,
        now,
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()

    private fun healthLabel(value: String): String = getString(
        when (value) {
            "reachable" -> R.string.health_reachable
            "unreachable" -> R.string.health_unreachable
            "incompatible" -> R.string.health_incompatible
            "conflicted" -> R.string.health_conflicted
            else -> R.string.health_unknown
        },
    )

    private fun healthColor(value: String): Int = color(
        when (value) {
            "reachable" -> R.color.collie_done
            "incompatible", "conflicted" -> R.color.collie_blocked
            else -> R.color.collie_muted
        },
    )

    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val POLL_MS = 4_000L
    }
}
