package cl.vintagemanager.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cl.vintagemanager.core.MonthFlow
import cl.vintagemanager.ui.theme.Olive
import cl.vintagemanager.ui.theme.Terracotta
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.core.common.shape.CorneredShape

/** Etiqueta corta para el eje: 1.2M, 350K. */
private fun shortClp(v: Double): String = when {
    v >= 1_000_000 -> "%.1fM".format(v / 1_000_000).replace('.', ',')
    v >= 1_000 -> "${(v / 1_000).toInt()}K"
    else -> v.toInt().toString()
}

/** Ingresos vs. egresos de los últimos 6 meses (Vico). */
@Composable
fun IncomeExpenseChart(flows: List<MonthFlow>, modifier: Modifier = Modifier) {
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(flows) {
        if (flows.isNotEmpty()) {
            producer.runTransaction {
                columnSeries {
                    series(flows.map { it.income })
                    series(flows.map { it.expense })
                }
            }
        }
    }
    val labels = flows.map { it.label }
    val bottomFormatter = remember(labels) {
        CartesianValueFormatter { _, x, _ -> labels.getOrElse(x.toInt()) { "" } }
    }
    val startFormatter = remember { CartesianValueFormatter { _, y, _ -> shortClp(y) } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberColumnCartesianLayer(
                    columnProvider = ColumnCartesianLayer.ColumnProvider.series(
                        rememberLineComponent(fill = fill(Olive), thickness = 10.dp, shape = CorneredShape.rounded(allDp = 3f)),
                        rememberLineComponent(fill = fill(Terracotta), thickness = 10.dp, shape = CorneredShape.rounded(allDp = 3f)),
                    ),
                ),
                startAxis = VerticalAxis.rememberStart(valueFormatter = startFormatter),
                bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = bottomFormatter),
            ),
            modelProducer = producer,
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(Olive, "Ingresos")
            Legend(Terracotta, "Egresos")
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
