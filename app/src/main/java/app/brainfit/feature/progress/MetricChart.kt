package app.brainfit.feature.progress

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.brainfit.core.designsystem.BrainTheme
import app.brainfit.core.designsystem.Motion
import app.brainfit.domain.progress.Better
import app.brainfit.domain.progress.ChartPoint
import app.brainfit.domain.progress.MetricSpec
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.point
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.compose.common.insets
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt

private val dayMonth = DateTimeFormatter.ofPattern("dd.MM")

/**
 * График метрики за период (Vico): одна точка — одна сессия, по порядку. Калибровочные сессии — полые
 * точки. Тап по точке показывает дату, уровень и значение. Для TalkBack — текстовое описание графика.
 */
@Composable
fun MetricChart(points: List<ChartPoint>, metric: MetricSpec, zone: ZoneId, modifier: Modifier = Modifier) {
    val colors = BrainTheme.colors
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(points) {
        producer.runTransaction { lineSeries { series(points.indices.toList(), points.map { it.value }) } }
    }
    fun date(index: Int): String =
        points.getOrNull(index)?.let { Instant.ofEpochMilli(it.at).atZone(zone).format(dayMonth) } ?: ""

    val solid = LineCartesianLayer.point(rememberShapeComponent(fill(colors.accent), CorneredShape.Pill), 8.dp)
    val hollow = LineCartesianLayer.point(
        rememberShapeComponent(fill(colors.card), CorneredShape.Pill, strokeFill = fill(colors.accent), strokeThickness = 2.dp),
        8.dp,
    )
    val pointProvider = remember(points, solid, hollow) {
        object : LineCartesianLayer.PointProvider {
            override fun getPoint(entry: LineCartesianLayerModel.Entry, seriesIndex: Int, extraStore: ExtraStore) =
                if (points.getOrNull(entry.x.roundToInt())?.calibration == true) hollow else solid

            override fun getLargestPoint(extraStore: ExtraStore) = solid
        }
    }
    val line = LineCartesianLayer.rememberLine(
        fill = remember(colors.accent) { LineCartesianLayer.LineFill.single(fill(colors.accent)) },
        pointProvider = pointProvider,
    )
    val range = remember(metric.share) {
        if (metric.share) CartesianLayerRangeProvider.fixed(minY = 0.0, maxY = 1.0) else CartesianLayerRangeProvider.auto()
    }
    val yFormatter = remember(metric) { CartesianValueFormatter { _, value, _ -> metric.format(value) } }
    val xFormatter = remember(points) { CartesianValueFormatter { _, value, _ -> date(value.roundToInt()) } }
    val labelSpacing = max(1, points.size / 4)
    val label = rememberAxisLabelComponent(color = colors.textSecondary, textSize = 12.sp)
    val axisLine = rememberAxisLineComponent(fill = fill(colors.outline))
    val guideline = rememberAxisGuidelineComponent(fill = fill(colors.surfaceSecondary))
    val marker = rememberDefaultCartesianMarker(
        label = rememberTextComponent(
            color = colors.textPrimary,
            textSize = 13.sp,
            padding = insets(horizontal = 8.dp, vertical = 4.dp),
            background = rememberShapeComponent(fill(colors.surfaceSecondary), CorneredShape.rounded(allPercent = 25)),
        ),
        valueFormatter = remember(points, metric) {
            DefaultCartesianMarker.ValueFormatter { _, targets ->
                val index = targets.firstOrNull()?.x?.roundToInt() ?: return@ValueFormatter ""
                val p = points.getOrNull(index) ?: return@ValueFormatter ""
                listOfNotNull(date(index), "ур. ${p.level}", metric.format(p.value), if (p.calibration) "калибровка" else null)
                    .joinToString(" · ")
            }
        },
    )

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(LineCartesianLayer.LineProvider.series(line), rangeProvider = range),
            startAxis = VerticalAxis.rememberStart(
                line = axisLine,
                label = label,
                valueFormatter = yFormatter,
                guideline = guideline,
                tick = null,
            ),
            bottomAxis = HorizontalAxis.rememberBottom(
                line = axisLine,
                label = label,
                valueFormatter = xFormatter,
                guideline = null,
                tick = null,
                itemPlacer = remember(labelSpacing) { HorizontalAxis.ItemPlacer.aligned(spacing = { labelSpacing }) },
            ),
            marker = marker,
        ),
        modelProducer = producer,
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .semantics { contentDescription = describe(points, metric, ::date) },
        // Весь период на одном экране: без прокрутки и масштаба.
        scrollState = rememberVicoScrollState(scrollEnabled = false),
        zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = remember { Zoom.Content }),
        // Линия вырастает при первом показе и плавно перестраивается при смене периода — в пределах 200 мс.
        animationSpec = tween(Motion.BASE, easing = Motion.Standard),
        animateIn = true,
    )
}

/** Текст графика для TalkBack: сколько сессий, первая, последняя и лучшая. */
private fun describe(points: List<ChartPoint>, metric: MetricSpec, date: (Int) -> String): String {
    if (points.isEmpty()) return "График пуст"
    val bestIndex = points.indices.reduce { b, i -> if (metric.isBetter(points[i].value, points[b].value)) i else b }
    val direction = if (metric.better == Better.LOWER) "меньше — лучше" else "больше — лучше"
    return "График: ${metric.title}, $direction. Сессий: ${points.size}. " +
        "Первая ${date(0)}: ${metric.format(points.first().value)}. " +
        "Последняя ${date(points.lastIndex)}: ${metric.format(points.last().value)}. " +
        "Лучшая ${date(bestIndex)}: ${metric.format(points[bestIndex].value)}."
}
