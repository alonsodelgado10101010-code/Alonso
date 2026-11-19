import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    @State private var s: Settings?
    @State private var iva = ""
    @State private var idpc = ""
    @State private var ppmLow = ""
    @State private var ppmHigh = ""
    @State private var thresholdUf = ""
    @State private var ufValue = ""
    @State private var initialCash = ""
    @State private var initialRemanente = ""
    @State private var lifetimes: [GarmentType: String] = [:]
    @State private var delicate = ""
    @State private var confirmDemo = false
    @State private var confirmClear = false
    @State private var importing = false
    @State private var pendingImport: URL?
    @State private var share: ShareItem?
    @State private var pinSheet = false

    private let times: [UInt32] = (0..<48).map { UInt32($0 * 30) }

    var body: some View {
        Form {
            if s != nil { sections } else { ProgressView() }
        }
        .navigationTitle("Ajustes")
        .onAppear { if s == nil { reload() } }
        .confirmationDialog("¿Cargar datos de demostración?", isPresented: $confirmDemo, titleVisibility: .visible) {
            Button("Cargar demo", role: .destructive) {
                model.mutate("Datos de demostración cargados") { try $0.loadDemoData(today: Fmt.today()) }
                reload()
            }
        } message: { Text("Se reemplazarán los datos actuales del negocio (los ajustes se mantienen). Exporta un respaldo antes.") }
        .confirmationDialog("¿Borrar todos los datos?", isPresented: $confirmClear, titleVisibility: .visible) {
            Button("Borrar", role: .destructive) { model.mutate("Se borraron los datos del negocio") { try $0.clearAllData() } }
        } message: { Text("Se eliminarán movimientos, prendas, cobros y pagos. No se puede deshacer.") }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .plainText, .data]) { result in
            if case .success(let url) = result { pendingImport = url }
        }
        .confirmationDialog("¿Restaurar respaldo?", isPresented: Binding(get: { pendingImport != nil }, set: { if !$0 { pendingImport = nil } }),
                            titleVisibility: .visible) {
            Button("Restaurar", role: .destructive) { importBackup() }
        } message: { Text("Los datos actuales se reemplazarán por los del archivo.") }
        .sheet(item: $share) { ShareSheet(items: [$0.url]) }
        .sheet(isPresented: $pinSheet) { PinSheet { pin in model.mutate("PIN configurado") { try $0.setPin(pin: pin) }; reload() } }
        .vintageScreen()
    }

    private func binding<T>(_ key: WritableKeyPath<Settings, T>) -> Binding<T> {
        Binding(get: { s![keyPath: key] }, set: { s![keyPath: key] = $0 })
    }

    @ViewBuilder private var sections: some View {
        Section("Datos de la tienda") {
            TextField("Nombre", text: binding(\.storeName))
            TextField("RUT", text: binding(\.storeRut))
            TextField("Giro", text: binding(\.storeGiro))
        }
        Section {
            Picker("Régimen", selection: Binding(get: { s!.regime }, set: { r in
                if model.mutate("Régimen actualizado con sus tasas por defecto", { try $0.setRegime(regime: r) }) != nil { reload() }
            })) { ForEach(TaxRegime.all, id: \.self) { Text($0.label).tag($0) } }
            rateField("IVA", $iva)
            rateField("Primera Categoría", $idpc)
            rateField("PPM ≤ umbral", $ppmLow)
            rateField("PPM > umbral", $ppmHigh)
            numberField("Umbral PPM (UF)", $thresholdUf)
            MoneyField("Valor UF", text: $ufValue)
            Picker("Vencimiento F29", selection: binding(\.f29DueDay)) {
                Text("Día 20 (facturación electrónica)").tag(UInt32(20))
                Text("Día 12 (papel)").tag(UInt32(12))
            }
            MoneyField("Caja inicial", text: $initialCash)
            MoneyField("Remanente IVA inicial", text: $initialRemanente)
        } header: { Text("Tributario") } footer: { Text("Las tasas cambian por ley: revísalas cada año con tu contador.") }
        Section("Inventario (FEFO)") {
            ForEach(GarmentType.all, id: \.self) { t in
                numberField("\(t.label) (días)", Binding(get: { lifetimes[t] ?? "" }, set: { lifetimes[t] = Fmt.digits($0) }))
            }
            ForEach(Era.all, id: \.self) { e in
                Toggle("Época \(e.label) en tendencia (+30%)", isOn: Binding(get: { s!.trendingEras.contains(e) }, set: { on in
                    if on { s!.trendingEras.append(e) } else { s!.trendingEras.removeAll { $0 == e } }
                }))
            }
            TextField("Materiales delicados (−20%)", text: $delicate)
        }
        Section {
            Picker("Revisión diaria", selection: binding(\.dailyCheckMinute)) { ForEach(times, id: \.self) { Text(Fmt.time($0)).tag($0) } }
            Picker("Silencio desde", selection: binding(\.quietStartMinute)) { ForEach(times, id: \.self) { Text(Fmt.time($0)).tag($0) } }
            Picker("Silencio hasta", selection: binding(\.quietEndMinute)) { ForEach(times, id: \.self) { Text(Fmt.time($0)).tag($0) } }
            Picker("Notificar desde", selection: binding(\.pushThreshold)) {
                Text("Alta y crítica (recomendado)").tag(AlertPriority.high)
                Text("Solo críticas").tag(AlertPriority.critical)
            }
            ForEach(AlertKind.all, id: \.self) { k in
                Toggle(k.label, isOn: Binding(get: { !s!.disabledAlertKinds.contains(k) }, set: { on in
                    if on { s!.disabledAlertKinds.removeAll { $0 == k } } else { s!.disabledAlertKinds.append(k) }
                }))
            }
            Button("Revisar alertas ahora") { Notifications.runCheck(core: model.core); model.message = "Revisión de alertas lista." }
        } header: { Text("Alertas y notificaciones") } footer: {
            Text("Máximo \(s!.maxPushPerDay) notificaciones al día; si hay más, llegan en un solo resumen.")
        }
        Section { Button("Guardar ajustes") { save() }.frame(maxWidth: .infinity).fontWeight(.semibold) }
        Section("Seguridad") {
            Text(s!.lockEnabled ? "Bloqueo con PIN activado" : "Sin bloqueo")
            Button(s!.lockEnabled ? "Cambiar PIN" : "Definir PIN") { pinSheet = true }
            if s!.lockEnabled {
                Toggle("Desbloquear con Face ID", isOn: Binding(get: { s!.biometricEnabled }, set: { on in
                    var copy = s!
                    copy.biometricEnabled = on
                    model.mutate { try $0.saveSettings(s: copy) }
                    reload()
                }))
                Button("Quitar bloqueo", role: .destructive) { model.mutate("Bloqueo desactivado") { try $0.setPin(pin: nil) }; reload() }
            }
        }
        Section("Respaldo y restauración") {
            Text("Último respaldo: \(s!.lastBackupDate.map(Fmt.cl) ?? "nunca")")
            Button("Exportar respaldo (JSON)") { exportBackup() }
            Button("Importar respaldo") { importing = true }
        }
        Section("Datos de demostración") {
            Text("40 prendas, 3 meses de movimientos, cobros, pagos y lotes para probar la app.").font(.caption)
            Button("Cargar demo") { confirmDemo = true }
            Button("Borrar todos los datos", role: .destructive) { confirmClear = true }
        }
        Section { Text("Vintage Manager · núcleo Rust v\(coreVersion())").font(.caption).foregroundStyle(.secondary) }
    }

    private func rateField(_ title: String, _ text: Binding<String>) -> some View {
        HStack {
            Text(title)
            Spacer()
            TextField("0", text: text).keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(maxWidth: 90)
            Text("%")
        }
    }

    private func numberField(_ title: String, _ text: Binding<String>) -> some View {
        HStack {
            Text(title)
            Spacer()
            TextField("0", text: text).keyboardType(.numberPad).multilineTextAlignment(.trailing).frame(maxWidth: 90)
        }
    }

    private func reload() {
        guard let loaded = model.query({ try $0.getSettings() }) else { return }
        s = loaded
        iva = Fmt.pctText(loaded.ivaRate)
        idpc = Fmt.pctText(loaded.idpcRate)
        ppmLow = Fmt.pctText(loaded.ppmRateLow)
        ppmHigh = Fmt.pctText(loaded.ppmRateHigh)
        thresholdUf = String(loaded.ppmThresholdUf)
        ufValue = String(loaded.ufValue)
        initialCash = String(loaded.initialCash)
        initialRemanente = String(loaded.initialIvaRemanente)
        lifetimes = Dictionary(uniqueKeysWithValues: GarmentType.all.map { t in
            (t, String(loaded.lifetimes.first { $0.garmentType == t }?.days ?? 90))
        })
        delicate = loaded.delicateMaterials.joined(separator: ", ")
    }

    private func save() {
        guard var copy = s else { return }
        guard let ivaR = Fmt.rate(fromPct: iva), let idpcR = Fmt.rate(fromPct: idpc),
              let lowR = Fmt.rate(fromPct: ppmLow), let highR = Fmt.rate(fromPct: ppmHigh) else {
            model.message = "Revisa las tasas: usa números como 19 o 0,125."
            return
        }
        if !copy.storeRut.isEmpty && !isValidRut(rut: copy.storeRut) {
            model.message = "El RUT de la tienda no es válido."
            return
        }
        copy.ivaRate = ivaR
        copy.idpcRate = idpcR
        copy.ppmRateLow = lowR
        copy.ppmRateHigh = highR
        copy.ppmThresholdUf = Int64(thresholdUf) ?? copy.ppmThresholdUf
        copy.ufValue = Int64(ufValue) ?? copy.ufValue
        copy.initialCash = Int64(initialCash) ?? 0
        copy.initialIvaRemanente = Int64(initialRemanente) ?? 0
        copy.lifetimes = GarmentType.all.map { GarmentLifetime(garmentType: $0, days: max(1, UInt32(lifetimes[$0] ?? "") ?? 1)) }
        copy.delicateMaterials = delicate.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        if model.mutate("Ajustes guardados", { try $0.saveSettings(s: copy) }) != nil {
            Notifications.scheduleBackgroundCheck(core: model.core)
            reload()
        }
    }

    private func exportBackup() {
        guard let json = model.mutate(nil, { try $0.exportBackup(today: Fmt.today()) }),
              let url = Exporter.write(json, name: "vintage_manager_\(Fmt.today()).json") else { return }
        share = ShareItem(url: url)
        reload()
    }

    private func importBackup() {
        guard let url = pendingImport else { return }
        pendingImport = nil
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        guard let json = try? String(contentsOf: url, encoding: .utf8) else {
            model.message = "No se pudo leer el archivo."
            return
        }
        if let n = model.mutate(nil, { try $0.importBackup(json: json) }) {
            model.message = "Respaldo restaurado (\(n) registros)."
            reload()
        }
    }
}

struct PinSheet: View {
    @Environment(\.dismiss) private var dismiss
    let onSave: (String) -> Void
    @State private var pin = ""
    @State private var confirm = ""

    var body: some View {
        NavigationStack {
            Form {
                SecureField("PIN (mínimo 4 dígitos)", text: $pin).keyboardType(.numberPad)
                SecureField("Repite el PIN", text: $confirm).keyboardType(.numberPad)
                if !confirm.isEmpty && pin != confirm { Text("Los PIN no coinciden").foregroundStyle(Palette.liquidar) }
            }
            .navigationTitle("Definir PIN")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Guardar") { onSave(pin); dismiss() }
                        .disabled(pin.count < 4 || pin != confirm || !pin.allSatisfy(\.isNumber))
                }
            }
        }
    }
}

struct AlertsView: View {
    @EnvironmentObject var model: AppModel
    @State private var alerts: [Alert] = []

    var body: some View {
        List {
            if alerts.isEmpty {
                EmptyStateView(icon: "checkmark.seal", title: "Sin alertas",
                               message: "No hay nada pendiente. Te avisaremos cuando algo requiera tu atención.")
                    .listRowBackground(Color.clear)
            }
            ForEach(AlertPriority.all, id: \.self) { p in
                let group = alerts.filter { $0.priority == p }
                if !group.isEmpty {
                    Section {
                        ForEach(group, id: \.key) { a in
                            Button { model.open(a.target) } label: { AlertRowView(alert: a) }
                                .swipeActions {
                                    Button("Posponer") {
                                        model.mutate("Alerta pospuesta hasta mañana") {
                                            try $0.snoozeAlerts(keys: [a.key], until: Fmt.addDays(Fmt.today(), 1))
                                        }
                                    }
                                    .tint(Palette.pronto)
                                }
                        }
                    } header: {
                        Text("Prioridad \(p.label.lowercased())")
                    } footer: {
                        Text(p == .critical || p == .high ? "Pueden llegar como notificación (máx. 3 al día)." : "Solo se muestran dentro de la app.")
                    }
                    .listRowBackground(Palette.card)
                }
            }
        }
        .navigationTitle("Bandeja de alertas")
        .task(id: model.version) { alerts = model.query { try $0.currentAlerts(today: Fmt.today()) } ?? [] }
        .vintageScreen()
    }
}
