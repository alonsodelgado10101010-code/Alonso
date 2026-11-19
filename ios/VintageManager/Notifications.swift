import BackgroundTasks
import Foundation
import UserNotifications

/// Publica lo que decide el motor de alertas en Rust. El tope de 3 diarias, la
/// deduplicación, la prioridad y el horario silencioso ya vienen aplicados.
enum Notifications {
    static let taskId = "cl.vintagemanager.alerts"
    static let categorySingle = "ALERTA_PAGO"
    static let categoryGeneral = "ALERTA"
    static let actionPaid = "MARCAR_PAGADO"
    static let actionSnooze = "POSPONER"

    static func requestPermission() {
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
        let paid = UNNotificationAction(identifier: actionPaid, title: "Marcar como pagado", options: [])
        let snooze = UNNotificationAction(identifier: actionSnooze, title: "Posponer 1 día", options: [])
        center.setNotificationCategories([
            UNNotificationCategory(identifier: categorySingle, actions: [paid, snooze], intentIdentifiers: []),
            UNNotificationCategory(identifier: categoryGeneral, actions: [snooze], intentIdentifiers: []),
        ])
    }

    /// Evalúa alertas con el núcleo y publica el plan resultante.
    static func runCheck(core: VintageCore) {
        guard let plan = try? core.evaluateAlerts(today: Fmt.today(), minuteOfDay: Fmt.minuteOfDay()) else { return }
        for n in plan.notifications { post(n) }
        scheduleBackgroundCheck(core: core, deferredMinute: plan.deferredUntilMinute)
    }

    static func post(_ n: PushNotification) {
        let content = UNMutableNotificationContent()
        content.title = n.title
        content.body = n.lines.isEmpty ? n.text : n.text + "\n" + n.lines.prefix(6).map { "• \($0)" }.joined(separator: "\n")
        content.sound = .default
        content.threadIdentifier = n.channel == .criticas ? "alertas_criticas" : "alertas_resumen"
        var info: [String: Any] = ["keys": n.alertKeys]
        if let id = n.payableId { info["payable"] = id }
        if let id = n.receivableId { info["receivable"] = id }
        switch n.target {
        case .payable(let id): info["target"] = "payable:\(id)"
        case .receivable(let id): info["target"] = "receivable:\(id)"
        case .garment(let id): info["target"] = "garment:\(id)"
        case .inventory: info["target"] = "inventory"
        case .taxes: info["target"] = "taxes"
        case .settings: info["target"] = "settings"
        case .dashboard: info["target"] = "dashboard"
        }
        content.userInfo = info
        content.categoryIdentifier = (n.payableId != nil || n.receivableId != nil) ? categorySingle : categoryGeneral
        let request = UNNotificationRequest(identifier: "vm-\(n.notificationId)", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    /// Retira de la pantalla las notificaciones cuya causa ya se resolvió.
    static func cancelResolved(core: VintageCore) {
        guard let ids = try? core.staleNotificationIds(today: Fmt.today()), !ids.isEmpty else { return }
        let names = ids.map { "vm-\($0)" }
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: names)
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: names)
    }

    static func target(from info: [AnyHashable: Any]) -> AlertTarget {
        let raw = info["target"] as? String ?? "dashboard"
        let parts = raw.split(separator: ":")
        let id = parts.count > 1 ? Int64(parts[1]) ?? 0 : 0
        switch parts.first ?? "" {
        case "payable": return .payable(id: id)
        case "receivable": return .receivable(id: id)
        case "garment": return .garment(id: id)
        case "inventory": return .inventory(semaphore: nil)
        case "taxes": return .taxes
        case "settings": return .settings
        default: return .dashboard
        }
    }

    // MARK: - Revisión diaria en segundo plano

    static func registerBackgroundTask() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskId, using: nil) { task in
            Task { @MainActor in
                runCheck(core: AppModel.shared.core)
                task.setTaskCompleted(success: true)
            }
        }
    }

    /// Programa la próxima revisión a la hora configurada (o al terminar el horario silencioso).
    static func scheduleBackgroundCheck(core: VintageCore, deferredMinute: UInt32? = nil) {
        let minute = deferredMinute ?? ((try? core.getSettings().dailyCheckMinute) ?? 540)
        var comps = Calendar.current.dateComponents([.year, .month, .day], from: Date())
        comps.hour = Int(minute / 60)
        comps.minute = Int(minute % 60)
        var next = Calendar.current.date(from: comps) ?? Date()
        if next <= Date() { next = Calendar.current.date(byAdding: .day, value: 1, to: next) ?? next }
        let request = BGAppRefreshTaskRequest(identifier: taskId)
        request.earliestBeginDate = next
        try? BGTaskScheduler.shared.submit(request)
    }
}

/// Recibe toques y acciones de las notificaciones.
final class NotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound, .list])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        let action = response.actionIdentifier
        Task { @MainActor in
            let model = AppModel.shared
            switch action {
            case Notifications.actionPaid:
                if let id = info["payable"] as? Int64 {
                    model.mutate("Pago registrado") { try $0.markPayablePaid(id: id, date: Fmt.today(), method: .transfer) }
                }
                if let id = info["receivable"] as? Int64 {
                    model.mutate("Cobro registrado") { try $0.markReceivablePaid(id: id, date: Fmt.today(), method: .transfer) }
                }
            case Notifications.actionSnooze:
                let keys = info["keys"] as? [String] ?? []
                model.mutate("Alerta pospuesta") { try $0.snoozeAlerts(keys: keys, until: Fmt.addDays(Fmt.today(), 1)) }
            default:
                model.open(Notifications.target(from: info))
            }
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [response.notification.request.identifier])
            completionHandler()
        }
    }
}
