import Charts
import SwiftUI

struct DashboardView: View {
    @EnvironmentObject var model: AppModel
    @State private var month = Calendar.current.dateComponents([.year, .month], from: Date())
    @State private var data: Dashboard?
    @State private var storeName = ""

    private var year: Int32 { Int32(month.year ?? 2026) }
    private var monthNumber: UInt32 { UInt32(month.month ?? 1) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                monthSelector
                if let d = data {
                    content(d)
                } else {
                    ProgressView().frame(maxWidth: .infinity).padding(48)
                }
                Spacer(minLength: 24)
            }
            .padding(.horizontal, 16)
        }
        .navigationTitle(storeName.isEmpty ? "Resumen" : storeName)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                NavigationLink(value: Route.alerts) {
                    Image(systemName: (data?.attention.isEmpty ?? true) ? "bell" : "bell.badge")
                }
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
                    Button { model.dashboardPath.append(.movement(id: 0, kind: .income, garmentId: nil)) } label: {
                        Label("Registrar venta", systemImage: "cart.badge.plus")
                    }
                    Button { model.dashboardPath.append(.movement(id: 0, kind: .expense, garmentId: nil)) } label: {
                        Label("Registrar gasto", systemImage: "doc.text")
                    }
                    Button { model.dashboardPath.append(.garmentEdit(0)) } label: {
                        Label("Agregar prenda", systemImage: "tshirt")
                    }
                } label: {
                    Image(systemName: "plus.circle.fill").font(.title2)
                }
            }
        }
        .task(id: "\(model.version)-\(year)-\(monthNumber)") { load() }
        .vintageScreen()
    }

    private func load() {
        data = model.query { try $0.dashboard(year: year, month: monthNumber, today: Fmt.today()) }
        storeName = model.query { try $0.getSettings().storeName } ?? ""
    }

    private func shift(_ delta: Int) {
        let date = Calendar.current.date(from: month) ?? Date()
        let next = Calendar.current.date(byAdding: .month, value: delta, to: date) ?? date
        month = Calendar.current.dateComponents([.year, .month], from: next)
    }

    private var monthSelector: some View {
        HStack {
            Button { shift(-1) } label: { Image(systemName: "chevron.left") }
            Spacer()
            Text("\(Fmt.month(monthNumber)) \(String(year))").font(.vintageTitle(.title2))
            Spacer()
            Button { shift(1) } label: { Image(systemName: "chevron.right") }
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private func content(_ d: Dashboard) -> some View {
        // 1. KPIs
        HStack(spacing: 12) {
            KpiCard(label: "Ventas del mes", amount: d.salesGross, icon: "arrow.up.right", accent: Palette.olive, caption: "con IVA")
            KpiCard(label: "Gastos del mes", amount: d.expensesGross, icon: "arrow.down.right", accent: Palette.terracotta)
        }
        HStack(spacing: 12) {
            KpiCard(label: "Utilidad estimada", amount: d.profitEstimate, icon: "banknote", accent: Palette.coffee, caption: "sin IVA")
            KpiCard(label: "Caja disponible", amount: d.cashAvailable, icon: "wallet.pass", accent: Palette.olive, caption: "estimada")
        }

        // 2. Impuestos
        Button { model.open(.taxes) } label: {
            CardView("Impuestos del mes (F29)") {
                if d.tax.remanenteNext > 0 {
                    AmountRow("Remanente IVA a favor", d.tax.remanenteNext, color: Palette.olive)
                } else {
                    AmountRow("IVA a pagar", d.tax.ivaToPay)
                }
                AmountRow("PPM estimado", d.tax.ppm)
                Divider()
                AmountRow("Total a pagar en el F29", d.tax.totalF29, bold: true)
                Text(d.tax.filed ? "Declarado ✓ · vencía el \(Fmt.cl(d.tax.dueDate))"
                                 : "Vence el \(Fmt.cl(d.tax.dueDate)) (\(Fmt.days(d.tax.daysToDue)))")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(d.tax.filed ? Palette.olive : (d.tax.daysToDue <= 3 ? Palette.liquidar : .secondary))
                Disclaimer(text: d.disclaimer)
            }
        }
        .buttonStyle(.plain)

        // 3. Por cobrar / por pagar
        HStack(spacing: 12) {
            Button { model.financeTab = 1; model.tab = .finance } label: {
                debtCard("Por cobrar", d.debts.receivablesTotal, d.debts.receivablesCount, d.debts.receivablesOverdue)
            }
            Button { model.financeTab = 2; model.tab = .finance } label: {
                debtCard("Por pagar", d.debts.payablesTotal, d.debts.payablesCount, d.debts.payablesOverdue)
            }
        }
        .buttonStyle(.plain)

        // 4. Inventario
        CardView("Inventario") {
            InfoRow("Prendas en tienda", "\(d.inventory.inStock)")
            AmountRow("Valor a costo", d.inventory.valueCost)
            AmountRow("Valor a precio de venta", d.inventory.valuePrice)
            HStack {
                Button { model.open(.inventory(semaphore: .rotarYa)) } label: {
                    Chip(text: "🟠 Rotar ya: \(d.inventory.rotarYa)", color: Palette.ya)
                }
                Button { model.open(.inventory(semaphore: .liquidar)) } label: {
                    Chip(text: "🔴 Liquidar: \(d.inventory.liquidar)", color: Palette.liquidar)
                }
            }
            .buttonStyle(.plain)
        }

        // 5. Requiere tu atención (máx. 5, ya ordenadas por el núcleo)
        CardView("Requiere tu atención") {
            if d.attention.isEmpty {
                Label("Todo al día. ¡Buen trabajo!", systemImage: "checkmark.seal").foregroundStyle(Palette.olive)
            }
            ForEach(d.attention, id: \.key) { a in
                Button { model.open(a.target) } label: { AlertRowView(alert: a) }.buttonStyle(.plain)
            }
        }

        // 6. Gráfico
        CardView("Ingresos vs. egresos (6 meses)") {
            IncomeExpenseChart(flows: d.chart)
        }
    }

    private func debtCard(_ title: String, _ total: Int64, _ count: UInt32, _ overdue: Int64) -> some View {
        CardView(title) {
            Text(Fmt.clp(total)).font(.headline)
            Text("\(count) pendientes").font(.caption).foregroundStyle(.secondary)
            if overdue > 0 {
                Text("Vencido: \(Fmt.clp(overdue))").font(.caption.weight(.bold)).foregroundStyle(Palette.liquidar)
            }
        }
    }
}

struct IncomeExpenseChart: View {
    let flows: [MonthFlow]

    private struct Bar: Identifiable {
        let id = UUID()
        let label: String
        let index: Int
        let series: String
        let amount: Int64
    }

    private var bars: [Bar] {
        flows.enumerated().flatMap { i, f in
            [Bar(label: f.label, index: i, series: "Ingresos", amount: f.income),
             Bar(label: f.label, index: i, series: "Egresos", amount: f.expense)]
        }
    }

    var body: some View {
        Chart(bars) { b in
            BarMark(x: .value("Mes", "\(b.index)·\(b.label)"), y: .value("Monto", b.amount))
                .foregroundStyle(by: .value("Serie", b.series))
                .position(by: .value("Serie", b.series))
        }
        .chartForegroundStyleScale(["Ingresos": Palette.olive, "Egresos": Palette.terracotta])
        .chartXAxis {
            AxisMarks { value in
                AxisValueLabel {
                    if let s = value.as(String.self) { Text(s.components(separatedBy: "·").last ?? s) }
                }
            }
        }
        .chartYAxis {
            AxisMarks { value in
                AxisGridLine()
                AxisValueLabel {
                    if let v = value.as(Int64.self) { Text(v >= 1_000_000 ? "\(v / 1_000_000)M" : "\(v / 1_000)K") }
                }
            }
        }
        .frame(height: 200)
    }
}
