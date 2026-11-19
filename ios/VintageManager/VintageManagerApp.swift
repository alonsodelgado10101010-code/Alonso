import LocalAuthentication
import SwiftUI
import UserNotifications

final class AppDelegate: NSObject, UIApplicationDelegate {
    let notificationDelegate = NotificationDelegate()

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = notificationDelegate
        Notifications.registerBackgroundTask()
        return true
    }
}

@main
struct VintageManagerApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    @StateObject private var model = AppModel.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            LockGate()
                .environmentObject(model)
                .tint(Palette.terracotta)
                .onAppear { Notifications.requestPermission() }
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                // Al abrir la app se revisan las alertas (respeta el tope diario).
                Notifications.runCheck(core: model.core)
                Notifications.cancelResolved(core: model.core)
            }
        }
    }
}

/// Bloqueo con PIN (verificado por el núcleo) o Face ID / huella.
struct LockGate: View {
    @EnvironmentObject var model: AppModel
    @State private var unlocked = false
    @State private var pin = ""
    @State private var error = false

    var body: some View {
        let settings = model.query { try $0.getSettings() }
        let locked = (settings?.lockEnabled ?? false) && settings?.pinHash != nil
        if unlocked || !locked {
            RootView()
        } else {
            VStack(spacing: 18) {
                Image(systemName: "lock.fill").font(.system(size: 48)).foregroundStyle(Palette.terracotta)
                Text("Vintage Manager").font(.vintageTitle(.largeTitle))
                Text("Ingresa tu PIN para continuar")
                SecureField("PIN", text: $pin)
                    .keyboardType(.numberPad)
                    .textFieldStyle(.roundedBorder)
                    .frame(maxWidth: 200)
                if error { Text("PIN incorrecto").foregroundStyle(Palette.liquidar) }
                Button("Desbloquear") {
                    if model.query({ try $0.verifyPin(pin: pin) }) == true { unlocked = true } else { error = true }
                }
                .buttonStyle(.borderedProminent)
                .disabled(pin.count < 4)
                if settings?.biometricEnabled == true {
                    Button { biometric() } label: { Label("Usar Face ID / huella", systemImage: "faceid") }
                }
            }
            .padding(32)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Palette.background.ignoresSafeArea())
            .onAppear { if settings?.biometricEnabled == true { biometric() } }
        }
    }

    private func biometric() {
        let ctx = LAContext()
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil) else { return }
        ctx.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: "Desbloquear Vintage Manager") { ok, _ in
            DispatchQueue.main.async { if ok { unlocked = true } }
        }
    }
}

/// Barra inferior con 4 pestañas: Resumen · Finanzas · Inventario · Ajustes.
struct RootView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        TabView(selection: $model.tab) {
            NavigationStack(path: $model.dashboardPath) {
                DashboardView().withRoutes()
            }
            .tabItem { Label("Resumen", systemImage: "square.grid.2x2") }
            .tag(AppTab.dashboard)

            NavigationStack(path: $model.financePath) {
                FinanceView().withRoutes()
            }
            .tabItem { Label("Finanzas", systemImage: "dollarsign.circle") }
            .tag(AppTab.finance)

            NavigationStack(path: $model.inventoryPath) {
                InventoryView().withRoutes()
            }
            .tabItem { Label("Inventario", systemImage: "tshirt") }
            .tag(AppTab.inventory)

            NavigationStack(path: $model.settingsPath) {
                SettingsView().withRoutes()
            }
            .tabItem { Label("Ajustes", systemImage: "gearshape") }
            .tag(AppTab.settings)
        }
    }
}

extension View {
    func withRoutes() -> some View {
        navigationDestination(for: Route.self) { route in
            switch route {
            case let .movement(id, kind, garmentId): MovementFormView(movementId: id, initialKind: kind, garmentId: garmentId)
            case .receivable(let id): ReceivableView(receivableId: id)
            case .payable(let id): PayableView(payableId: id)
            case .suppliers: SuppliersView()
            case .garment(let id): GarmentDetailView(garmentId: id)
            case .garmentEdit(let id): GarmentFormView(garmentId: id)
            case .lots: LotsView()
            case .metrics: MetricsView()
            case .alerts: AlertsView()
            }
        }
    }
}
