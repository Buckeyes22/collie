package com.lateapex.collie.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Rect
import android.os.Bundle
import android.util.AttributeSet
import android.view.accessibility.AccessibilityEvent
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.lateapex.collie.R
import com.lateapex.collie.network.PackMember
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

enum class PackFormationRole { LEAD, DEPUTY, PEER }

data class PackFormationNode(
    val member: PackMember,
    val role: PackFormationRole,
    val x: Float,
    val y: Float,
    val row: Int,
)

/** The web formation's four ring treatments, separate from the member's diagnostic word. */
internal enum class PackRingState {
    LOUD,
    LIVE,
    STALE,
    UNKNOWN,
}

internal fun packRingState(member: PackMember, hostState: DashboardHostState?): PackRingState = when {
    member.health == "incompatible" || member.health == "conflicted" -> PackRingState.LOUD
    hostState == DashboardHostState.LIVE -> PackRingState.LIVE
    hostState == DashboardHostState.STALE -> PackRingState.STALE
    hostState == DashboardHostState.UNKNOWN -> PackRingState.UNKNOWN
    member.health == "reachable" -> PackRingState.LIVE
    member.lastSeenAt > 0L -> PackRingState.STALE
    else -> PackRingState.UNKNOWN
}

/** Pure port of the web formation geometry in `pack-formation.tsx`. */
object PackFormationLayout {
    const val VIEW_WIDTH = 360f
    private const val CENTER_X = VIEW_WIDTH / 2f
    private const val APEX_Y = 58f
    private const val ROW_GAP = 110f
    private const val FAN_GAP = 96f
    private const val FAN_DY = 84f
    private const val FAN_X0 = 68f
    private const val FAN_DX = 38f
    private const val FAN_PER_V = 6
    private const val V_GAP = 80f
    const val NODE_RADIUS = 26f
    private const val BOTTOM_PAD = 44f

    fun nodes(members: List<PackMember>, deputyId: String?): List<PackFormationNode> {
        val lead = members.firstOrNull(PackMember::isLead)
        val deputy = deputyId?.let { id -> members.firstOrNull { it.id == id && !it.isLead } }
        val peers = members.filter { it !== lead && it !== deputy }
        val nodes = mutableListOf<PackFormationNode>()
        var y = APEX_Y
        if (lead != null) nodes += PackFormationNode(lead, PackFormationRole.LEAD, CENTER_X, y, 0)
        if (deputy != null) {
            y += ROW_GAP
            nodes += PackFormationNode(deputy, PackFormationRole.DEPUTY, CENTER_X, y, 1)
        }
        val fanTop = if (nodes.isEmpty()) APEX_Y else y + FAN_GAP
        val baseRow = nodes.size
        peers.forEachIndexed { index, member ->
            val v = floor(index.toDouble() / FAN_PER_V).toInt()
            val within = index % FAN_PER_V
            val rank = within / 2
            val side = if (within % 2 == 0) -1 else 1
            nodes += PackFormationNode(
                member = member,
                role = PackFormationRole.PEER,
                x = CENTER_X + side * (FAN_X0 + rank * FAN_DX),
                y = fanTop + v * (2 * FAN_DY + V_GAP) + rank * FAN_DY,
                row = baseRow + v * 3 + rank,
            )
        }
        return nodes
    }

    fun height(nodes: List<PackFormationNode>): Float =
        if (nodes.isEmpty()) 0f else nodes.maxOf(PackFormationNode::y) + NODE_RADIUS + BOTTOM_PAD
}

/** Thumb-sized, accessible native rendering of the canonical pack formation. */
class PackFormationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.collie_rule)
        style = Paint.Style.STROKE
    }
    private val nodeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.collie_card)
        style = Paint.Style.FILL
    }
    private val nodeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.collie_border)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.collie_muted)
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
    }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.collie_foreground)
        textSize = 11f
        textAlign = Paint.Align.CENTER
    }
    private val badgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.collie_muted_surface)
        style = Paint.Style.FILL
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.collie_muted)
        textSize = 9f
        textAlign = Paint.Align.CENTER
    }
    private val countFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.collie_blocked)
        style = Paint.Style.FILL
    }
    private val countTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.collie_background)
        textSize = 10f
        textAlign = Paint.Align.CENTER
    }

    private var nodes: List<PackFormationNode> = emptyList()
    private var blockedCounts: Map<String, Int> = emptyMap()
    private var hostStates: Map<String, DashboardHostState> = emptyMap()
    private var selectedListener: ((PackMember) -> Unit)? = null
    private val accessibilityHelper = object : ExploreByTouchHelper(this) {
        override fun onPopulateNodeForHost(node: AccessibilityNodeInfoCompat) {
            super.onPopulateNodeForHost(node)
            node.contentDescription = resources.getQuantityString(
                R.plurals.pack_formation_accessibility,
                nodes.size,
                nodes.size,
            )
        }

        override fun getVirtualViewAt(x: Float, y: Float): Int = nodeIndexAt(x, y)

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            virtualViewIds += nodes.indices
        }

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            val formationNode = nodes.getOrNull(virtualViewId) ?: return
            node.className = android.widget.Button::class.java.name
            node.contentDescription = nodeDescription(formationNode)
            node.isClickable = true
            node.isFocusable = true
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            val scale = width / PackFormationLayout.VIEW_WIDTH
            val radius = 36f * scale
            val cx = formationNode.x * scale
            val cy = formationNode.y * scale
            node.setBoundsInParent(Rect(
                (cx - radius).toInt(),
                (cy - radius).toInt(),
                (cx + radius).toInt(),
                (cy + radius).toInt(),
            ))
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            val member = nodes.getOrNull(virtualViewId)?.member ?: return false
            selectedListener?.invoke(member)
            sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
            return true
        }
    }

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)
    }

    internal fun submit(
        members: List<PackMember>,
        deputyId: String?,
        blocked: Map<String, Int>,
        hostStates: Map<String, DashboardHostState> = emptyMap(),
        onSelected: (PackMember) -> Unit,
    ) {
        nodes = PackFormationLayout.nodes(members, deputyId)
        blockedCounts = blocked
        this.hostStates = hostStates
        selectedListener = onSelected
        contentDescription = null
        accessibilityHelper.invalidateRoot()
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val scale = measuredWidth / PackFormationLayout.VIEW_WIDTH
        val wantedHeight = (PackFormationLayout.height(nodes) * scale).toInt()
        setMeasuredDimension(measuredWidth, resolveSize(wantedHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (nodes.isEmpty()) return
        val scale = width / PackFormationLayout.VIEW_WIDTH
        canvas.save()
        canvas.scale(scale, scale)
        drawConnectors(canvas)
        nodes.forEach { drawNode(canvas, it) }
        canvas.restore()
    }

    private fun drawConnectors(canvas: Canvas) {
        val lead = nodes.firstOrNull { it.role == PackFormationRole.LEAD } ?: return
        nodes.filter { it !== lead }.forEach { node ->
            linePaint.strokeWidth = if (node.role == PackFormationRole.DEPUTY) 2.5f else 1.25f
            canvas.drawPath(connector(lead, node), linePaint)
        }
    }

    private fun drawNode(canvas: Canvas, node: PackFormationNode) {
        val radius = PackFormationLayout.NODE_RADIUS
        canvas.drawCircle(node.x, node.y, radius, nodeFillPaint)
        canvas.drawCircle(node.x, node.y, radius, nodeBorderPaint)
        val ring = packRingState(node.member, hostStates[node.member.id])
        ringPaint.color = when (ring) {
            PackRingState.LOUD -> color(R.color.collie_blocked)
            PackRingState.LIVE -> color(R.color.collie_done)
            PackRingState.STALE -> color(R.color.collie_working)
            PackRingState.UNKNOWN -> color(R.color.collie_unknown)
        }
        ringPaint.pathEffect = when (ring) {
            PackRingState.LOUD, PackRingState.LIVE -> null
            PackRingState.STALE -> DashPathEffect(floatArrayOf(7f, 5f), 0f)
            PackRingState.UNKNOWN -> DashPathEffect(floatArrayOf(2f, 5f), 0f)
        }
        canvas.drawCircle(node.x, node.y, radius, ringPaint)
        drawServerGlyph(canvas, node.x, node.y)
        drawRoleBadge(canvas, node)
        drawBlockedCount(canvas, node)
        canvas.drawText(clipName(node.member.name.ifBlank { node.member.id }), node.x, node.y + radius + 15f, namePaint)
    }

    private fun drawServerGlyph(canvas: Canvas, x: Float, y: Float) {
        val body = RectF(x - 9f, y - 8f, x + 9f, y + 8f)
        canvas.drawRoundRect(body, 2f, 2f, glyphPaint)
        canvas.drawLine(x - 5f, y - 3f, x + 5f, y - 3f, glyphPaint)
        canvas.drawLine(x - 5f, y + 2f, x + 2f, y + 2f, glyphPaint)
        canvas.drawCircle(x + 5f, y + 2f, 1f, glyphPaint)
    }

    private fun drawRoleBadge(canvas: Canvas, node: PackFormationNode) {
        if (node.role == PackFormationRole.PEER) return
        val word = resources.getString(
            if (node.role == PackFormationRole.LEAD) R.string.pack_role_lead else R.string.pack_role_deputy,
        ).uppercase(resources.configuration.locales[0])
        val width = word.length * 5.6f + 26f
        val top = node.y - PackFormationLayout.NODE_RADIUS - 28f
        canvas.drawRoundRect(RectF(node.x - width / 2f, top, node.x + width / 2f, top + 17f), 2f, 2f, badgeFillPaint)
        canvas.drawText(word, node.x, top + 12f, badgeTextPaint)
    }

    private fun drawBlockedCount(canvas: Canvas, node: PackFormationNode) {
        val count = blockedCounts[node.member.id] ?: 0
        if (count <= 0) return
        val word = count.toString()
        val width = max(16f, word.length * 7f + 10f)
        val left = node.x + 10f
        val top = node.y - PackFormationLayout.NODE_RADIUS - 6f
        canvas.drawRoundRect(RectF(left, top, left + width, top + 16f), 8f, 8f, countFillPaint)
        canvas.drawText(word, left + width / 2f, top + 11.5f, countTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP || nodes.isEmpty()) return true
        val selected = nodes.getOrNull(nodeIndexAt(event.x, event.y))
        if (selected != null) {
            performClick()
            selectedListener?.invoke(selected.member)
        }
        return true
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibilityHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun performClick(): Boolean = super.performClick()

    private fun connector(from: PackFormationNode, to: PackFormationNode): Path {
        val radius = PackFormationLayout.NODE_RADIUS
        val y1 = to.y - radius
        return Path().apply {
            if (to.x == from.x) {
                moveTo(from.x, from.y + radius + 22f)
                lineTo(to.x, y1)
            } else {
                val dx = to.x - from.x
                val dy = to.y - from.y
                val length = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                val sx = from.x + dx / length * radius
                val sy = from.y + dy / length * radius
                moveTo(sx, sy)
                quadTo(to.x, (sy + y1) / 2f, to.x, y1)
            }
        }
    }

    private fun clipName(name: String, maxChars: Int = 9): String =
        if (name.length <= maxChars) name else "${name.take(maxChars - 1)}…"

    private fun nodeIndexAt(screenX: Float, screenY: Float): Int {
        if (nodes.isEmpty() || width == 0) return ExploreByTouchHelper.INVALID_ID
        val scale = width / PackFormationLayout.VIEW_WIDTH
        val x = screenX / scale
        val y = screenY / scale
        val closest = nodes.indices.minByOrNull { index ->
            val node = nodes[index]
            hypot((node.x - x).toDouble(), (node.y - y).toDouble())
        } ?: return ExploreByTouchHelper.INVALID_ID
        val node = nodes[closest]
        return if (hypot((node.x - x).toDouble(), (node.y - y).toDouble()) <= 36f) {
            closest
        } else {
            ExploreByTouchHelper.INVALID_ID
        }
    }

    private fun nodeDescription(node: PackFormationNode): String {
        val name = node.member.name.ifBlank { node.member.id }
        val health = resources.getString(when (node.member.health) {
            "reachable" -> R.string.health_reachable
            "unreachable" -> R.string.health_unreachable
            "incompatible" -> R.string.health_incompatible
            "conflicted" -> R.string.health_conflicted
            else -> R.string.health_unknown
        })
        return if (node.role == PackFormationRole.PEER) {
            resources.getString(R.string.pack_node_accessibility_plain, name, health)
        } else {
            val role = resources.getString(
                if (node.role == PackFormationRole.LEAD) R.string.pack_role_lead else R.string.pack_role_deputy,
            )
            resources.getString(R.string.pack_node_accessibility, name, role, health)
        }
    }

    private fun color(id: Int): Int = ContextCompat.getColor(context, id)
}
