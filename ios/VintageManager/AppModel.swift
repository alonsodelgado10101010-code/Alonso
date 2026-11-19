import Foundation
import SwiftUI

/// Pestañas principales.
enum AppTab: Hashable { case dashboard, finance, inventory, settings }

/// Destinos de navegación.
enum Route: Hashable {
    case movement(id: Int64, kind: MovementKind, garmentId: Int64?)
    case receivable(Int64)
    case payable(Int64)
    case suppliers
    case garment(Int64)
    case garmentEdit(Int64)
    case lots
    case metrics
    case alerts
}

/// Envoltorio fino sobre el núcleo Rust (`VintageCore`). No contiene lógica de
/// negocio: IVA, PPM, FLR y prioridades las calcula siempre el núcleo.
@MainActor
final class AppModel: ObservableObject {
    static let shared = AppModel()

    let core: VintageCore
    let databasePath: String

    /// Aumenta con cada escritura: las pantallas se recargan al cambiar.
    @Published private(set) var version = 0
    @Published var message: String?
    @Published var tab: AppTab = .dashboard
    @Published var dashboardPath: [Route] = []
    @Published var financePath: [Route] = []
    @Published var financeTab: Int = 0
    @Published var inventoryPath: [Route] = []
    @Published var inventorySemaphore: Semaphore?
    @Published var settingsPath: [Route] = []

    private init() {
        // La base vive en Application Support: iOS la conserva al actualizar la app
        // y la incluye en los respaldos de iCloud / Finder.
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        databasePath = dir.appendingPathComponent("vintage_manager.db").path
        do {
            core = try VintageCore(dbPath: databasePath)
        } catch {
            fatalError("No se pudo abrir la base de datos: \(error.userMessage)")
        }
    }

    /// Lectura: devuelve nil y muestra el error si algo falla.
    func query<T>(_ body: (VintageCore) throws -> T) -> T? {
        do { return try body(core) } catch {
            message = error.userMessage
            return nil
        }
    }

    /// Escritura: avisa a las pantallas y retira notificaciones resueltas.
    @discardableResult
    func mutate<T>(_ success: String? = nil, _ body: (VintageCore) throws -> T) -> T? {
        do {
            let result = try body(core)
            version += 1
            if let success { message = success }
            Notifications.cancelResolved(core: core)
            return result
        } catch {
            message = error.userMessage
            return nil
        }
    }

    /// Navega al destino de una alerta (desde la app o una notificación).
    func open(_ target: AlertTarget) {
        switch target {
        case .dashboard:
            tab = .dashboard
        case .taxes:
            financeTab = 3
            financePath = []
            tab = .finance
        case .payable(let id):
            financeTab = 2
            financePath = [.payable(id)]
            tab = .finance
        case .receivable(let id):
            financeTab = 1
            financePath = [.receivable(id)]
            tab = .finance
        case .inventory(let semaphore):
            inventorySemaphore = semaphore
            inventoryPath = []
            tab = .inventory
        case .garment(let id):
            inventoryPath = [.garment(id)]
            tab = .inventory
        case .settings:
            tab = .settings
        }
    }
}

extension Error {
    /// Mensaje legible a partir de los errores tipados del núcleo.
    var userMessage: String {
        if let e = self as? CoreError {
            switch e {
            case .Validation(let msg): return msg.prefix(1).uppercased() + msg.dropFirst()
            case .NotFound(let entity, _): return "No se encontró \(entity)."
            case .Database(let msg): return "Error de base de datos: \(msg)"
            case .Backup(let msg): return msg.prefix(1).uppercased() + msg.dropFirst()
            }
        }
        return localizedDescription
    }
}
