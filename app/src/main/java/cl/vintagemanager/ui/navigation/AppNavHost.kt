package cl.vintagemanager.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cl.vintagemanager.core.AlertTarget
import cl.vintagemanager.core.Semaphore
import cl.vintagemanager.ui.alerts.AlertsScreen
import cl.vintagemanager.ui.dashboard.DashboardScreen
import cl.vintagemanager.ui.finance.FinanceScreen
import cl.vintagemanager.ui.finance.MovementFormScreen
import cl.vintagemanager.ui.finance.PayableScreen
import cl.vintagemanager.ui.finance.ReceivableScreen
import cl.vintagemanager.ui.finance.SuppliersScreen
import cl.vintagemanager.ui.inventory.GarmentDetailScreen
import cl.vintagemanager.ui.inventory.GarmentFormScreen
import cl.vintagemanager.ui.inventory.InventoryScreen
import cl.vintagemanager.ui.inventory.LotsScreen
import cl.vintagemanager.ui.inventory.MetricsScreen
import cl.vintagemanager.ui.settings.SettingsScreen

object Routes {
    const val DASHBOARD = "dashboard"
    const val FINANCE = "finance?tab={tab}"
    const val INVENTORY = "inventory?sem={sem}"
    const val SETTINGS = "settings"
    const val MOVEMENT = "movement/{id}?kind={kind}&garment={garment}"
    const val RECEIVABLE = "receivable/{id}"
    const val PAYABLE = "payable/{id}"
    const val SUPPLIERS = "suppliers"
    const val GARMENT = "garment/{id}"
    const val GARMENT_EDIT = "garmentEdit/{id}"
    const val LOTS = "lots"
    const val METRICS = "metrics"
    const val ALERTS = "alerts"

    fun finance(tab: Int = 0) = "finance?tab=$tab"
    fun inventory(sem: Semaphore? = null) = "inventory?sem=${sem?.name ?: ""}"
    fun newMovement(kind: String, garment: Long = 0) = "movement/0?kind=$kind&garment=$garment"
    fun editMovement(id: Long) = "movement/$id?kind=&garment=0"
    fun receivable(id: Long) = "receivable/$id"
    fun payable(id: Long) = "payable/$id"
    fun garment(id: Long) = "garment/$id"
    fun garmentEdit(id: Long) = "garmentEdit/$id"

    const val TAB_TAXES = 3
}

/** Ruta de destino para una alerta (en la app o desde una notificación). */
fun routeFor(target: AlertTarget): String = when (target) {
    is AlertTarget.Dashboard -> Routes.DASHBOARD
    is AlertTarget.Taxes -> Routes.finance(Routes.TAB_TAXES)
    is AlertTarget.Payable -> Routes.payable(target.id)
    is AlertTarget.Receivable -> Routes.receivable(target.id)
    is AlertTarget.Inventory -> Routes.inventory(target.semaphore)
    is AlertTarget.Garment -> Routes.garment(target.id)
    is AlertTarget.Settings -> Routes.SETTINGS
}

private data class Tab(val route: String, val base: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.DASHBOARD, "dashboard", "Resumen", Icons.Filled.Dashboard),
    Tab(Routes.finance(), "finance", "Finanzas", Icons.Filled.Payments),
    Tab(Routes.inventory(), "inventory", "Inventario", Icons.Filled.Checkroom),
    Tab(Routes.SETTINGS, "settings", "Ajustes", Icons.Filled.Settings),
)

@Composable
fun AppNavHost(
    pendingRoute: String?,
    onRouteConsumed: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    LaunchedEffect(pendingRoute) {
        if (!pendingRoute.isNullOrBlank()) {
            navController.navigate(pendingRoute) { launchSingleTop = true }
            onRouteConsumed()
        }
    }
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route.orEmpty()
    val showBar = tabs.any { currentRoute.startsWith(it.base) }

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute.startsWith(tab.base),
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
        ) {
            val nav: (String) -> Unit = { navController.navigate(it) }
            val back: () -> Unit = { navController.popBackStack() }

            composable(Routes.DASHBOARD) { DashboardScreen(onNavigate = nav) }
            composable(
                Routes.FINANCE,
                arguments = listOf(navArgument("tab") { type = NavType.IntType; defaultValue = 0 }),
            ) { entry -> FinanceScreen(initialTab = entry.arguments?.getInt("tab") ?: 0, onNavigate = nav) }
            composable(
                Routes.INVENTORY,
                arguments = listOf(navArgument("sem") { type = NavType.StringType; defaultValue = "" }),
            ) { entry ->
                val sem = entry.arguments?.getString("sem").orEmpty()
                InventoryScreen(
                    initialSemaphore = Semaphore.entries.firstOrNull { it.name == sem },
                    onNavigate = nav,
                )
            }
            composable(Routes.SETTINGS) { SettingsScreen(onNavigate = nav) }
            composable(Routes.ALERTS) { AlertsScreen(onNavigate = nav, onBack = back) }
            composable(
                Routes.MOVEMENT,
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType },
                    navArgument("kind") { type = NavType.StringType; defaultValue = "" },
                    navArgument("garment") { type = NavType.LongType; defaultValue = 0L },
                ),
            ) { MovementFormScreen(onBack = back) }
            composable(Routes.RECEIVABLE, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                ReceivableScreen(onBack = back)
            }
            composable(Routes.PAYABLE, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                PayableScreen(onBack = back, onNavigate = nav)
            }
            composable(Routes.SUPPLIERS) { SuppliersScreen(onBack = back) }
            composable(Routes.GARMENT, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                GarmentDetailScreen(onBack = back, onNavigate = nav)
            }
            composable(Routes.GARMENT_EDIT, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                GarmentFormScreen(onBack = back)
            }
            composable(Routes.LOTS) { LotsScreen(onBack = back) }
            composable(Routes.METRICS) { MetricsScreen(onBack = back, onNavigate = nav) }
        }
    }
}
