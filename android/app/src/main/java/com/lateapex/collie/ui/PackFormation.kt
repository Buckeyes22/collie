package com.lateapex.collie.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Rect
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
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
    const val NAME_LABEL_GAP = 5f
    private const val NAME_LABEL_BOTTOM_PAD = 8f

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

    fun height(nodes: List<PackFormationNode>, nameTextHeight: Float): Float =
        if (nodes.isEmpty()) 0f else nodes.maxOf(PackFormationNode::y) + NODE_RADIUS +
            max(BOTTOM_PAD, NAME_LABEL_GAP + nameTextHeight + NAME_LABEL_BOTTOM_PAD)
}

internal object PackFormationTextLayout {
    fun canvasTextSize(sp: Float, metrics: android.util.DisplayMetrics, canvasScale: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics) / canvasScale.coerceAtLeast(0.01f)

    fun textHeight(paint: Paint, verticalPadding: Float): Float =
        paint.fontMetrics.run { descent - ascent + verticalPadding }

    fun topInset(topmostLabelTop: Float, topPadding: Float = 8f): Float =
        max(0f, topPadding - topmostLabelTop)

    fun measuredHeight(contentHeight: Float, topInset: Float, canvasScale: Float): Int =
        ((contentHeight + topInset) * canvasScale).toInt()

    fun nameTop(nodeY: Float, radius: Float): Float = nodeY + radius + PackFormationLayout.NAME_LABEL_GAP

    fun nameBaseline(labelTop: Float, paint: Paint): Float = labelTop - paint.ascent()

    fun nameBottom(baseline: Float, paint: Paint): Float = baseline + paint.descent()

    fun separateRows(
        source: List<PackFormationNode>,
        gap: Float,
        horizontalBounds: (PackFormationNode) -> Pair<Float, Float>,
        visualTop: (PackFormationNode) -> Float,
        nameBottom: (PackFormationNode) -> Float,
    ): List<PackFormationNode> {
        val positioned = mutableListOf<PackFormationNode>()
        source.groupBy(PackFormationNode::y).toSortedMap().values.forEach { row ->
            val shift = row.maxOf { candidate ->
                val (left, right) = horizontalBounds(candidate)
                (positioned.asSequence()
                    .filter { previous ->
                        val (previousLeft, previousRight) = horizontalBounds(previous)
                        left < previousRight && right > previousLeft
                    }
                    .maxOfOrNull { previous -> nameBottom(previous) + gap - visualTop(candidate) }
                    ?: 0f).coerceAtLeast(0f)
            }
            positioned += row.map { it.copy(y = it.y + shift) }
        }
        val byIdentity = positioned.associateBy { it.member.id }
        return source.map { byIdentity[it.member.id] ?: it }
    }
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
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.collie_foreground)
        textAlign = Paint.Align.CENTER
    }
    private val badgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.collie_muted_surface)
        style = Paint.Style.FILL
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.collie_muted)
        textAlign = Paint.Align.CENTER
    }
    private val countFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.collie_blocked)
        style = Paint.Style.FILL
    }
    private val countTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.collie_background)
        textAlign = Paint.Align.CENTER
    }

    private var sourceNodes: List<PackFormationNode> = emptyList()
    private var nodes: List<PackFormationNode> = emptyList()
    private var blockedCounts: Map<String, Int> = emptyMap()
    private var hostStates: Map<String, DashboardHostState> = emptyMap()
    private var selectedListener: ((PackMember) -> Unit)? = null
    private var labelTopInset = 0f
    internal val laidOutNodes: List<PackFormationNode> get() = nodes
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
            val cy = (formationNode.y + labelTopInset) * scale
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
        sourceNodes = PackFormationLayout.nodes(members, deputyId)
        nodes = sourceNodes
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
        updateTextPaints(scale)
        nodes = spaceNodesForLabels()
        labelTopInset = calculateLabelTopInset()
        val nameHeight = PackFormationTextLayout.textHeight(namePaint, 0f)
        val wantedHeight = PackFormationTextLayout.measuredHeight(
            PackFormationLayout.height(nodes, nameHeight),
            labelTopInset,
            scale,
        )
        setMeasuredDimension(measuredWidth, resolveSize(wantedHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (nodes.isEmpty()) return
        val scale = width / PackFormationLayout.VIEW_WIDTH
        updateTextPaints(scale)
        canvas.save()
        canvas.scale(scale, scale)
        canvas.translate(0f, labelTopInset)
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
        val nameTop = PackFormationTextLayout.nameTop(node.y, radius)
        canvas.drawText(
            clipName(node.member.name.ifBlank { node.member.id }),
            node.x,
            PackFormationTextLayout.nameBaseline(nameTop, namePaint),
            namePaint,
        )
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
        val width = badgeTextPaint.measureText(word) + 16f
        val height = PackFormationTextLayout.textHeight(badgeTextPaint, 8f)
        val countTop = if ((blockedCounts[node.member.id] ?: 0) > 0) {
            countBadgeRect(node).top
        } else {
            node.y - 32f
        }
        val bottom = countTop - 5f
        val top = bottom - height
        canvas.drawRoundRect(RectF(node.x - width / 2f, top, node.x + width / 2f, bottom), 2f, 2f, badgeFillPaint)
        val baseline = top + (height - (badgeTextPaint.descent() - badgeTextPaint.ascent())) / 2f - badgeTextPaint.ascent()
        canvas.drawText(word, node.x, baseline, badgeTextPaint)
    }

    private fun drawBlockedCount(canvas: Canvas, node: PackFormationNode) {
        val count = blockedCounts[node.member.id] ?: 0
        if (count <= 0) return
        val word = count.toString()
        val rect = countBadgeRect(node)
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, countFillPaint)
        val baseline = rect.top + (rect.height() - (countTextPaint.descent() - countTextPaint.ascent())) / 2f - countTextPaint.ascent()
        canvas.drawText(word, rect.centerX(), baseline, countTextPaint)
    }

    private fun countBadgeRect(node: PackFormationNode): RectF {
        val count = (blockedCounts[node.member.id] ?: 0).toString()
        val height = max(16f, PackFormationTextLayout.textHeight(countTextPaint, 6f))
        val width = max(16f, countTextPaint.measureText(count) + 10f)
        val left = node.x + 10f
        val bottom = node.y - PackFormationLayout.NODE_RADIUS - 6f + 16f
        return RectF(left, bottom - height, left + width, bottom)
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

    private fun clipName(name: String, maxWidth: Float = 72f): String =
        TextUtils.ellipsize(name, namePaint, maxWidth, TextUtils.TruncateAt.END).toString()

    private fun updateTextPaints(canvasScale: Float) {
        val metrics = resources.displayMetrics
        namePaint.textSize = PackFormationTextLayout.canvasTextSize(11f, metrics, canvasScale)
        badgeTextPaint.textSize = PackFormationTextLayout.canvasTextSize(9f, metrics, canvasScale)
        countTextPaint.textSize = PackFormationTextLayout.canvasTextSize(10f, metrics, canvasScale)
    }

    private fun calculateLabelTopInset(): Float {
        if (nodes.isEmpty()) return 0f
        val top = nodes.flatMap { node ->
            buildList {
                if (node.role != PackFormationRole.PEER) {
                    val height = PackFormationTextLayout.textHeight(badgeTextPaint, 8f)
                    val countTop = if ((blockedCounts[node.member.id] ?: 0) > 0) {
                        countBadgeRect(node).top
                    } else {
                        node.y - 32f
                    }
                    add(countTop - 5f - height)
                }
                if ((blockedCounts[node.member.id] ?: 0) > 0) add(countBadgeRect(node).top)
            }
        }.minOrNull() ?: 0f
        return PackFormationTextLayout.topInset(top)
    }

    private fun spaceNodesForLabels(): List<PackFormationNode> {
        if (sourceNodes.isEmpty()) return emptyList()
        val nameHeight = PackFormationTextLayout.textHeight(namePaint, 0f)
        return PackFormationTextLayout.separateRows(
            source = sourceNodes,
            gap = 8f,
            horizontalBounds = ::visualHorizontalBounds,
            visualTop = ::visualTop,
            nameBottom = { node ->
                PackFormationTextLayout.nameTop(node.y, PackFormationLayout.NODE_RADIUS) + nameHeight
            },
        )
    }

    private fun visualHorizontalBounds(node: PackFormationNode): Pair<Float, Float> {
        var left = node.x - max(PackFormationLayout.NODE_RADIUS, 36f)
        var right = node.x + max(PackFormationLayout.NODE_RADIUS, 36f)
        if (node.role != PackFormationRole.PEER) {
            val label = resources.getString(
                if (node.role == PackFormationRole.LEAD) R.string.pack_role_lead else R.string.pack_role_deputy,
            ).uppercase(resources.configuration.locales[0])
            val halfWidth = (badgeTextPaint.measureText(label) + 16f) / 2f
            left = minOf(left, node.x - halfWidth)
            right = max(right, node.x + halfWidth)
        }
        if ((blockedCounts[node.member.id] ?: 0) > 0) {
            val countRect = countBadgeRect(node)
            left = minOf(left, countRect.left)
            right = max(right, countRect.right)
        }
        return left to right
    }

    private fun visualTop(node: PackFormationNode): Float {
        var top = node.y - PackFormationLayout.NODE_RADIUS
        if ((blockedCounts[node.member.id] ?: 0) > 0) top = minOf(top, countBadgeRect(node).top)
        if (node.role != PackFormationRole.PEER) {
            val height = PackFormationTextLayout.textHeight(badgeTextPaint, 8f)
            val countTop = if ((blockedCounts[node.member.id] ?: 0) > 0) {
                countBadgeRect(node).top
            } else {
                node.y - 32f
            }
            top = minOf(top, countTop - 5f - height)
        }
        return top
    }

    private fun nodeIndexAt(screenX: Float, screenY: Float): Int {
        if (nodes.isEmpty() || width == 0) return ExploreByTouchHelper.INVALID_ID
        val scale = width / PackFormationLayout.VIEW_WIDTH
        val x = screenX / scale
        val y = screenY / scale - labelTopInset
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
