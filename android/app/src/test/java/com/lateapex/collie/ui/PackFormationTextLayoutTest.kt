package com.lateapex.collie.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Paint
import android.text.TextPaint
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.network.PackMember
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PackFormationTextLayoutTest {
    @Test
    fun `200 percent text keeps name below node and within measured height at narrow width`() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(base.resources.configuration).apply { fontScale = 2f }
        val context = base.createConfigurationContext(configuration)
        val density = context.resources.displayMetrics.density
        val widthPx = (240f * density).toInt()
        val canvasScale = widthPx / PackFormationLayout.VIEW_WIDTH
        val textSize = PackFormationTextLayout.canvasTextSize(11f, context.resources.displayMetrics, canvasScale)
        val normalTextSize = PackFormationTextLayout.canvasTextSize(11f, base.resources.displayMetrics, canvasScale)
        val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.textSize = textSize }
        val nameHeight = PackFormationTextLayout.textHeight(namePaint, verticalPadding = 0f)
        val members = listOf(
            PackMember(
                id = "lead",
                name = "A very long server name",
                isLead = true,
                health = "reachable",
                lastSeenAt = 1,
                secretBehind = false,
                provisional = false,
            ),
        )
        val nodes = PackFormationLayout.nodes(members, deputyId = null)
        val view = PackFormationView(context)
        view.submit(members, deputyId = null, blocked = emptyMap(), onSelected = {})
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )

        val node = nodes.single()
        val circleBottom = node.y + PackFormationLayout.NODE_RADIUS
        val labelTop = PackFormationTextLayout.nameTop(node.y, PackFormationLayout.NODE_RADIUS)
        val baseline = PackFormationTextLayout.nameBaseline(labelTop, namePaint)
        val labelBottom = PackFormationTextLayout.nameBottom(baseline, namePaint)
        val measuredHeightInCanvasUnits = view.measuredHeight / canvasScale

        assertTrue("the configured 200% font scale should enlarge the diagram label", textSize > normalTextSize)
        assertTrue(labelTop >= circleBottom + PackFormationLayout.NAME_LABEL_GAP)
        assertTrue("the name descender must fit inside the measured view", labelBottom <= measuredHeightInCanvasUnits)
    }

    @Test
    fun `200 percent lead deputy and six peers do not collide across visual rows`() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(base.resources.configuration).apply { fontScale = 2f }
        val context = base.createConfigurationContext(configuration)
        val density = context.resources.displayMetrics.density
        val widthPx = (240f * density).toInt()
        val canvasScale = widthPx / PackFormationLayout.VIEW_WIDTH
        val metrics = context.resources.displayMetrics
        val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = PackFormationTextLayout.canvasTextSize(11f, metrics, canvasScale)
        }
        val rolePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = PackFormationTextLayout.canvasTextSize(9f, metrics, canvasScale)
        }
        val countPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = PackFormationTextLayout.canvasTextSize(10f, metrics, canvasScale)
        }
        val members = listOf(member("lead", lead = true), member("deputy")) +
            (1..6).map { member("peer-$it") }
        val blocked = members.associate { it.id to 3 }
        val view = PackFormationView(context)
        view.submit(members, deputyId = "deputy", blocked = blocked, onSelected = {})
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val nodes = view.laidOutNodes

        fun horizontalBounds(node: PackFormationNode): Pair<Float, Float> {
            var left = node.x - maxOf(PackFormationLayout.NODE_RADIUS, 36f)
            var right = node.x + maxOf(PackFormationLayout.NODE_RADIUS, 36f)
            if (node.role != PackFormationRole.PEER) {
                val role = if (node.role == PackFormationRole.LEAD) "LEAD" else "DEPUTY"
                val half = (rolePaint.measureText(role) + 16f) / 2f
                left = minOf(left, node.x - half)
                right = maxOf(right, node.x + half)
            }
            if ((blocked[node.member.id] ?: 0) > 0) {
                val count = "3"
                val countWidth = maxOf(16f, countPaint.measureText(count) + 10f)
                left = minOf(left, node.x + 10f)
                right = maxOf(right, node.x + 10f + countWidth)
            }
            return left to right
        }

        fun visualTop(node: PackFormationNode): Float {
            var top = node.y - PackFormationLayout.NODE_RADIUS
            val countHeight = maxOf(16f, PackFormationTextLayout.textHeight(countPaint, 6f))
            val countTop = node.y - 16f - countHeight
            top = minOf(top, countTop)
            if (node.role != PackFormationRole.PEER) {
                val roleHeight = PackFormationTextLayout.textHeight(rolePaint, 8f)
                top = minOf(top, countTop - 5f - roleHeight)
            }
            return top
        }

        val nameHeight = PackFormationTextLayout.textHeight(namePaint, 0f)
        val ordered = nodes.sortedBy(PackFormationNode::y)
        for (i in ordered.indices) {
            val current = ordered[i]
            val currentBounds = horizontalBounds(current)
            for (previous in ordered.take(i)) {
                val previousBounds = horizontalBounds(previous)
                val horizontalOverlap = currentBounds.first < previousBounds.second &&
                    currentBounds.second > previousBounds.first
                if (horizontalOverlap) {
                    val previousNameBottom = PackFormationTextLayout.nameTop(
                        previous.y,
                        PackFormationLayout.NODE_RADIUS,
                    ) + nameHeight
                    assertTrue(
                        "${previous.member.id} name overlaps ${current.member.id} badge/circle",
                        previousNameBottom + 8f <= visualTop(current),
                    )
                }
            }
        }
    }

    @Test
    fun `empty formation measures zero height`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = PackFormationView(context)
        view.submit(emptyList(), deputyId = null, blocked = emptyMap(), onSelected = {})
        view.measure(
            View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )

        assertTrue("empty formation should not reserve label inset height", view.measuredHeight == 0)
    }

    private fun member(id: String, lead: Boolean = false) = PackMember(
        id = id,
        name = "A long $id node label",
        isLead = lead,
        health = "reachable",
        lastSeenAt = 1,
        secretBehind = false,
        provisional = false,
    )
}
