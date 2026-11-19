import SwiftUI
import UIKit

struct FinanceView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        VStack(spacing: 0) {
            Picker("Sección", selection: $model.financeTab) {
                Text("Movimientos").tag(0)
                Text("Cobros").tag(1)
                Text("Pagos").tag(2)
                Text("Impuestos").tag(3)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            switch model.financeTab {
            case 1: ReceivablesList()
            case 2: PayablesList()
            case 3: TaxesView()
            default: MovementsList()
            }
        }
        .navigationTitle("Finanzas")
        .vintageScreen()
    }
}

// MARK: - Movimientos

struct MovementsList: View {
    @EnvironmentObject var model: AppModel
    @State private var month = Calendar.current.dateComponents([.year, .month], from: Date())
    @State private var kind: MovementKind?
    @State private var category: Category?
    @State private var movements: [Movement] = []
    @State private var share: ShareItem?

    private var filter: MovementFilter {
        MovementFilter(year: Int32(month.year ?? 2026), month: UInt32(month.month ?? 1), kind: kind, category: category)
    }

    var body: some View {
        List {
            Section {
                HStack {
                    Button { shift(-1) } label: { Image(systemName: "chevron.left") }.buttonStyle(.borderless)
                    Spacer()
                    Text("\(Fmt.month(UInt32(month.month ?? 1))) \(String(month.year ?? 2026))").font(.vintageTitle(.headline))
                    Spacer()
                    Button { shift(1) } label: { Image(systemName: "chevron.right") }.buttonStyle(.borderless)
                }
                Picker("Tipo", selection: $kind) {
                    Text("Todos").tag(MovementKind?.none)
                    Text("Ingresos").tag(MovementKind?.some(.income))
                    Text("Egresos").tag(MovementKind?.some(.expense))
                }
                .pickerStyle(.segmented)
                if let k = kind {
                    Picker("Categoría", selection: $category) {
                        Text("Todas").tag(Category?.none)
                        ForEach(Category.options(for: k), id: \.self) { Text($0.label).tag(Category?.some($0)) }
                    }
                }
                let income = movements.filter { $0.kind == .income }.reduce(Int64(0)) { $0 + $1.gross }
                let expense = movements.filter { $0.kind == .expense }.reduce(Int64(0)) { $0 + $1.gross }
                HStack {
                    Text("Ingresos \(Fmt.clp(income))").foregroundStyle(Palette.olive)
                    Spacer()
                    Text("Egresos \(Fmt.clp(expense))").foregroundStyle(Palette.terracotta)
                }
                .font(.footnote.weight(.semibold))
            }
            if movements.isEmpty {
                EmptyStateView(icon: "doc.text", title: "Sin movimientos este mes",
                               message: "Registra tus ventas y gastos para ver tus impuestos y tu caja al día.")
                    .listRowBackground(Color.clear)
            }
            ForEach(movements, id: \.id) { m in
                NavigationLink(value: Route.movement(id: m.id, kind: m.kind, garmentId: nil)) { MovementRow(m: m) }
            }
            .listRowBackground(Palette.card)
        }
        .onChange(of: kind) { _ in category = nil }
        .task(id: "\(model.version)-\(month.year ?? 0)-\(month.month ?? 0)-\(String(describing: kind))-\(String(describing: category))") {
            movements = model.query { try $0.listMovements(filter: filter) } ?? []
        }
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button {
                    if let csv = model.query({ try $0.movementsCsv(filter: filter) }),
                       let url = Exporter.write(csv, name: "movimientos_\(month.year ?? 0)-\(month.month ?? 0).csv") {
                        share = ShareItem(url: url)
                    }
                } label: { Image(systemName: "square.and.arrow.up") }
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                NavigationLink(value: Route.movement(id: 0, kind: .income, garmentId: nil)) { Image(systemName: "plus") }
            }
        }
        .sheet(item: $share) { ShareSheet(items: [$0.url]) }
    }

    private func shift(_ delta: Int) {
        let date = Calendar.current.date(from: month) ?? Date()
        month = Calendar.current.dateComponents([.year, .month], from: Calendar.current.date(byAdding: .month, value: delta, to: date) ?? date)
    }
}

struct MovementRow: View {
    let m: Movement
    var body: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 2) {
                Text(m.category.label).font(.subheadline.weight(.semibold))
                Text([Fmt.cl(m.date), m.documentType.label, m.paymentMethod.label].joined(separator: " · "))
                    .font(.caption).foregroundStyle(.secondary)
                if !m.description.isEmpty { Text(m.description).font(.caption) }
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text((m.kind == .income ? "+" : "−") + Fmt.clp(m.gross))
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(m.kind == .income ? Palette.olive : Palette.terracotta)
                if m.iva > 0 {
                    Text("IVA \(Fmt.clp(m.iva))" + (m.kind == .expense ? (m.ivaCreditable ? " · crédito" : " · sin crédito") : ""))
                        .font(.caption2).foregroundStyle(.secondary)
                }
            }
        }
    }
}

// MARK: - Cobros y pagos

struct ShowPaidToggle: View {
    @Binding var showPaid: Bool
    var body: some View { Toggle("Mostrar también los pagados", isOn: $showPaid) }
}

struct ReceivablesList: View {
    @EnvironmentObject var model: AppModel
    @State private var showPaid = false
    @State private var items: [Receivable] = []

    var body: some View {
        List {
            Section {
                ShowPaidToggle(showPaid: $showPaid)
                AmountRow("Total por cobrar", items.filter { $0.status != .paid }.reduce(Int64(0)) { $0 + $1.remaining }, bold: true)
            }
            if items.isEmpty {
                EmptyStateView(icon: "person.crop.circle.badge.clock", title: "No tienes cobros pendientes",
                               message: "Registra ventas a crédito, consignaciones o apartados para no olvidarlos.")
                    .listRowBackground(Color.clear)
            }
            ForEach(items, id: \.id) { r in
                NavigationLink(value: Route.receivable(r.id)) {
                    DebtRow(title: r.customer, subtitle: "\(r.concept.label) · vence \(Fmt.cl(r.dueDate)) (\(Fmt.days(r.daysToDue)))",
                            amount: r.remaining, status: r.status)
                }
            }
            .listRowBackground(Palette.card)
        }
        .task(id: "\(model.version)-\(showPaid)") {
            items = model.query { try $0.listReceivables(today: Fmt.today(), onlyOpen: !showPaid) } ?? []
        }
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                NavigationLink(value: Route.receivable(0)) { Image(systemName: "plus") }
            }
        }
    }
}

struct PayablesList: View {
    @EnvironmentObject var model: AppModel
    @State private var showPaid = false
    @State private var items: [Payable] = []

    var body: some View {
        List {
            Section {
                ShowPaidToggle(showPaid: $showPaid)
                AmountRow("Total por pagar", items.filter { $0.status != .paid }.reduce(Int64(0)) { $0 + $1.remaining }, bold: true)
                NavigationLink(value: Route.suppliers) { Label("Proveedores", systemImage: "person.2") }
            }
            if items.isEmpty {
                EmptyStateView(icon: "building.columns", title: "Sin pagos pendientes a proveedores",
                               message: "Registra facturas por pagar y te avisaremos antes de que venzan.")
                    .listRowBackground(Color.clear)
            }
            ForEach(items, id: \.id) { p in
                NavigationLink(value: Route.payable(p.id)) {
                    DebtRow(title: p.supplierName,
                            subtitle: "\(p.documentType.label)\(p.documentNumber.map { " N° \($0)" } ?? "") · vence \(Fmt.cl(p.dueDate)) (\(Fmt.days(p.daysToDue)))",
                            amount: p.remaining, status: p.status)
                }
            }
            .listRowBackground(Palette.card)
        }
        .task(id: "\(model.version)-\(showPaid)") {
            items = model.query { try $0.listPayables(today: Fmt.today(), onlyOpen: !showPaid) } ?? []
        }
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                NavigationLink(value: Route.payable(0)) { Image(systemName: "plus") }
            }
        }
    }
}

struct DebtRow: View {
    let title: String
    let subtitle: String
    let amount: Int64
    let status: DebtStatus
    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.weight(.semibold))
                Text(subtitle).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 4) {
                Text(Fmt.clp(amount)).font(.subheadline.weight(.bold))
                Chip(text: status.label, color: status.color)
            }
        }
    }
}

// MARK: - Impuestos

struct TaxesView: View {
    @EnvironmentObject var model: AppModel
    @State private var year = Int32(Calendar.current.component(.year, from: Date()))
    @State private var months: [MonthlyTax] = []
    @State private var annual: AnnualTax?
    @State private var share: ShareItem?

    var body: some View {
        List {
            Section {
                HStack {
                    Button { year -= 1 } label: { Image(systemName: "chevron.left") }.buttonStyle(.borderless)
                    Spacer()
                    Text("Año comercial \(String(year))").font(.vintageTitle(.headline))
                    Spacer()
                    Button { year += 1 } label: { Image(systemName: "chevron.right") }.buttonStyle(.borderless)
                }
                HStack {
                    Button { exportPdf() } label: { Label("PDF", systemImage: "doc.richtext") }.buttonStyle(.bordered)
                    Spacer()
                    Button { exportCsv() } label: { Label("Excel / CSV", systemImage: "tablecells") }.buttonStyle(.bordered)
                }
                Disclaimer()
            }
            if let a = annual {
                Section("Impuesto a la Renta estimado") {
                    AmountRow("Ingresos netos acumulados", a.incomeNet)
                    AmountRow("Costos y gastos netos", a.costsNet)
                    AmountRow("Base imponible", a.base)
                    AmountRow("Primera Categoría (\(Fmt.pct(a.idpcRate)))", a.tax)
                    AmountRow("PPM pagados", a.ppmPaid)
                    if a.possibleRefund {
                        AmountRow("Posible devolución en Operación Renta", -a.balance, bold: true, color: Palette.olive)
                    } else {
                        AmountRow("Saldo estimado a pagar", a.balance, bold: true)
                    }
                    if a.monthsElapsed > 0 {
                        AmountRow("Base proyectada a diciembre", a.projectedBase)
                        AmountRow("Impuesto proyectado", a.projectedTax)
                        AmountRow("Saldo proyectado", a.projectedBalance)
                    }
                    Text(a.note).font(.caption).foregroundStyle(.secondary)
                }
                .listRowBackground(Palette.card)
            }
            Section("Desglose mensual (F29)") {
                ForEach(months, id: \.month) { m in MonthTaxRow(m: m) }
            }
            .listRowBackground(Palette.card)
        }
        .task(id: "\(model.version)-\(year)") {
            months = model.query { try $0.taxYear(year: year, today: Fmt.today()) } ?? []
            annual = model.query { try $0.annualTax(year: year, today: Fmt.today()) }
        }
        .sheet(item: $share) { ShareSheet(items: [$0.url]) }
    }

    private func exportCsv() {
        if let csv = model.query({ try $0.taxCsv(year: year, today: Fmt.today()) }),
           let url = Exporter.write(csv, name: "impuestos_\(year).csv") {
            share = ShareItem(url: url)
        }
    }

    private func exportPdf() {
        guard let annual, let settings = model.query({ try $0.getSettings() }) else { return }
        if let url = Exporter.taxPdf(store: settings.storeName, rut: settings.storeRut, year: year, months: months, annual: annual) {
            share = ShareItem(url: url)
        }
    }
}

struct MonthTaxRow: View {
    @EnvironmentObject var model: AppModel
    let m: MonthlyTax
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("\(Fmt.month(m.month)) \(String(m.year))").font(.vintageTitle(.headline))
                Spacer()
                Toggle("Declarado", isOn: Binding(get: { m.filed }, set: { filed in
                    if filed {
                        model.mutate("F29 marcado como declarado") { try $0.markF29Filed(year: m.year, month: m.month, date: Fmt.today()) }
                    } else {
                        model.mutate { try $0.unmarkF29Filed(year: m.year, month: m.month) }
                    }
                }))
                .labelsHidden()
                Text("Declarado").font(.caption)
            }
            AmountRow("IVA débito (ventas)", m.ivaDebit)
            AmountRow("IVA crédito (compras con factura)", m.ivaCredit)
            if m.remanentePrev > 0 { AmountRow("Remanente mes anterior", m.remanentePrev) }
            if m.remanenteNext > 0 {
                AmountRow("Remanente a favor (pasa al mes siguiente)", m.remanenteNext, color: Palette.olive)
            } else {
                AmountRow("IVA a pagar", m.ivaToPay)
            }
            AmountRow("PPM (\(Fmt.pct(m.ppmRate)) de \(Fmt.clp(m.ppmBase)))", m.ppm)
            Divider()
            AmountRow("Total F29", m.totalF29, bold: true)
            Text("Vence el \(Fmt.cl(m.dueDate))" + (!m.filed && m.daysToDue >= 0 ? " (\(Fmt.days(m.daysToDue)))" : ""))
                .font(.caption)
                .foregroundStyle(!m.filed && (0...3).contains(m.daysToDue) ? Palette.liquidar : .secondary)
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Exportación y compartir

struct ShareItem: Identifiable {
    let id = UUID()
    let url: URL
}

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

enum Exporter {
    static func directory() -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("exports", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func write(_ text: String, name: String) -> URL? {
        let url = directory().appendingPathComponent(name)
        do { try text.write(to: url, atomically: true, encoding: .utf8); return url } catch { return nil }
    }

    /// PDF A4 apaisado con el desglose del F29 y la renta (solo dibuja lo que calculó el núcleo).
    static func taxPdf(store: String, rut: String, year: Int32, months: [MonthlyTax], annual: AnnualTax) -> URL? {
        let bounds = CGRect(x: 0, y: 0, width: 842, height: 595)
        let url = directory().appendingPathComponent("impuestos_\(year).pdf")
        let title: [NSAttributedString.Key: Any] = [.font: UIFont(name: "Georgia-Bold", size: 18) ?? UIFont.boldSystemFont(ofSize: 18)]
        let bold: [NSAttributedString.Key: Any] = [.font: UIFont.boldSystemFont(ofSize: 9)]
        let normal: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 9)]
        let headers = ["Mes", "Ventas", "IVA débito", "IVA crédito", "Rem. ant.", "IVA a pagar", "Rem. sig.", "Base PPM", "Tasa", "PPM", "Total F29", "Vence"]
        let xs: [CGFloat] = [36, 110, 180, 250, 320, 385, 455, 525, 595, 640, 700, 770]
        do {
            try UIGraphicsPDFRenderer(bounds: bounds).writePDF(to: url) { ctx in
                ctx.beginPage()
                var y: CGFloat = 30
                ("Resumen tributario \(year) — \(store)" as NSString).draw(at: CGPoint(x: 36, y: y), withAttributes: title)
                y += 26
                ("RUT: \(rut.isEmpty ? "—" : rut)" as NSString).draw(at: CGPoint(x: 36, y: y), withAttributes: normal)
                y += 22
                for (i, h) in headers.enumerated() { (h as NSString).draw(at: CGPoint(x: xs[i], y: y), withAttributes: bold) }
                y += 16
                for m in months {
                    let values = [Fmt.month(m.month), Fmt.clp(m.salesGross), Fmt.clp(m.ivaDebit), Fmt.clp(m.ivaCredit),
                                  Fmt.clp(m.remanentePrev), Fmt.clp(m.ivaToPay), Fmt.clp(m.remanenteNext), Fmt.clp(m.ppmBase),
                                  Fmt.pct(m.ppmRate), Fmt.clp(m.ppm), Fmt.clp(m.totalF29), Fmt.cl(m.dueDate)]
                    for (i, v) in values.enumerated() { (v as NSString).draw(at: CGPoint(x: xs[i], y: y), withAttributes: normal) }
                    y += 14
                }
                y += 16
                ("Impuesto a la Renta estimado (\(Fmt.pct(annual.idpcRate)))" as NSString).draw(at: CGPoint(x: 36, y: y), withAttributes: bold)
                y += 16
                let rows: [(String, Int64)] = [("Ingresos netos", annual.incomeNet), ("Costos y gastos netos", annual.costsNet),
                                               ("Base imponible", annual.base), ("Impuesto Primera Categoría", annual.tax),
                                               ("PPM pagados", annual.ppmPaid), ("Saldo estimado", annual.balance),
                                               ("Proyección impuesto a diciembre", annual.projectedTax)]
                for (k, v) in rows {
                    (k as NSString).draw(at: CGPoint(x: 36, y: y), withAttributes: normal)
                    (Fmt.clp(v) as NSString).draw(at: CGPoint(x: 220, y: y), withAttributes: normal)
                    y += 13
                }
                y += 10
                (annual.note as NSString).draw(in: CGRect(x: 36, y: y, width: 770, height: 30), withAttributes: normal)
                y += 30
                ("Cálculo referencial. Confirma con tu contador antes de declarar en el SII." as NSString)
                    .draw(at: CGPoint(x: 36, y: y), withAttributes: bold)
            }
            return url
        } catch {
            return nil
        }
    }
}
