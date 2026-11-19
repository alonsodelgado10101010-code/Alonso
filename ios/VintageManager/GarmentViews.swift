import SwiftUI
import UIKit

// MARK: - Ficha de prenda

struct GarmentDetailView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let garmentId: Int64
    @State private var view: GarmentView?
    @State private var history: [PriceChange] = []
    @State private var discounting = false
    @State private var selling = false
    @State private var confirmDelete = false

    var body: some View {
        Group {
            if let v = view {
                content(v)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(view?.garment.sku ?? "Prenda")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                NavigationLink(value: Route.garmentEdit(garmentId)) { Image(systemName: "pencil") }
                Button(role: .destructive) { confirmDelete = true } label: { Image(systemName: "trash") }
            }
        }
        .task(id: model.version) {
            view = model.query { try $0.getGarment(id: garmentId, today: Fmt.today()) }
            history = model.query { try $0.priceHistory(id: garmentId) } ?? []
        }
        .sheet(isPresented: $discounting) {
            if let v = view { DiscountSheet(garment: v).environmentObject(model) }
        }
        .sheet(isPresented: $selling) {
            if let v = view { SellSheet(garment: v.garment).environmentObject(model) }
        }
        .confirmationDialog("¿Eliminar prenda?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Eliminar", role: .destructive) {
                if model.mutate("Prenda eliminada", { try $0.deleteGarment(id: garmentId) }) != nil { dismiss() }
            }
        } message: { Text("Se borrará la ficha y su historial de precios.") }
        .vintageScreen()
    }

    private func content(_ v: GarmentView) -> some View {
        let g = v.garment
        let active = g.status != .vendida && g.status != .donada
        return List {
            if !g.photoPaths.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(g.photoPaths, id: \.self) { p in
                            if let img = PhotoStore.image(p) {
                                Image(uiImage: img).resizable().scaledToFill()
                                    .frame(width: 180, height: 180).clipShape(RoundedRectangle(cornerRadius: 16))
                            }
                        }
                    }
                }
                .listRowBackground(Color.clear)
            }
            Section {
                Text("\(g.garmentType.label) · \(g.brand)").font(.vintageTitle(.title3))
                Text(Fmt.clp(g.price)).font(.title.weight(.bold)).foregroundStyle(Palette.terracotta)
            }
            Section {
                HStack { Text("Rotación (FEFO)").font(.headline); Spacer(); Chip(text: v.semaphore.label, color: v.semaphore.color) }
                InfoRow("Fecha Límite de Rotación", Fmt.cl(v.flr))
                InfoRow("Días restantes", v.daysRemaining < 0 ? "Vencida hace \(-v.daysRemaining) días" : "\(v.daysRemaining)")
                if g.status == .enReparacion {
                    Text("En reparación/lavandería: la FLR está pausada.").font(.caption)
                }
                Text("Sugerencia: \(v.suggestedAction)").font(.subheadline.weight(.semibold))
                if active {
                    HStack {
                        Button("Aplicar rebaja") { discounting = true }.buttonStyle(.bordered)
                        Spacer()
                        Button("Vender") { selling = true }.buttonStyle(.borderedProminent)
                    }
                }
            }
            Section("Ficha") {
                InfoRow("Época", g.era.label)
                InfoRow("Talla", g.size.isEmpty ? "—" : g.size)
                InfoRow("Color", g.color.isEmpty ? "—" : g.color)
                InfoRow("Material", g.material.isEmpty ? "—" : g.material)
                InfoRow("Conservación", g.condition.label)
                InfoRow("Temporada", g.season.label)
                InfoRow("Ingreso", Fmt.cl(g.intakeDate))
                AmountRow("Costo", g.cost)
                if let sold = g.soldPrice { AmountRow("Vendida en (\(g.soldDate.map(Fmt.cl) ?? ""))", sold) }
                if !g.description.isEmpty { Text(g.description).font(.subheadline) }
            }
            if active {
                Section("Estado y ubicación") {
                    Picker("Estado", selection: Binding(get: { g.status }, set: { s in
                        model.mutate("Estado actualizado") { try $0.setGarmentStatus(id: garmentId, status: s, today: Fmt.today()) }
                    })) {
                        ForEach(GarmentStatus.editable, id: \.self) { Text($0.label).tag($0) }
                    }
                    Picker("Ubicación", selection: Binding(get: { g.location }, set: { l in
                        model.mutate("Ubicación actualizada") { try $0.setGarmentLocation(id: garmentId, location: l) }
                    })) {
                        ForEach(StoreLocation.all, id: \.self) { Text($0.label).tag($0) }
                    }
                }
            }
            if let label = QRRenderer.image(payload: v.qrPayload, caption: [g.sku, Fmt.clp(g.price)]) {
                Section("Etiqueta QR") {
                    HStack {
                        Image(uiImage: label).resizable().interpolation(.none).scaledToFit().frame(width: 120)
                        Spacer()
                        Button { printLabel(label, sku: g.sku) } label: { Label("Imprimir", systemImage: "printer") }
                            .buttonStyle(.bordered)
                    }
                }
            }
            if !history.isEmpty {
                Section("Historial de precios") {
                    ForEach(history, id: \.id) { h in
                        InfoRow("\(Fmt.cl(h.date)) · \(h.reason)", "\(Fmt.clp(h.oldPrice)) → \(Fmt.clp(h.newPrice))")
                    }
                }
            }
        }
    }

    private func printLabel(_ image: UIImage, sku: String) {
        let info = UIPrintInfo(dictionary: nil)
        info.outputType = .photo
        info.jobName = "Etiqueta \(sku)"
        let controller = UIPrintInteractionController.shared
        controller.printInfo = info
        controller.printingItem = image
        controller.present(animated: true)
    }
}

/// Rebaja con vista previa del núcleo: nuevo precio, margen y aviso bajo costo.
struct DiscountSheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let garment: GarmentView
    @State private var pct: Double = 20

    private var preview: DiscountPreview? {
        try? model.core.previewDiscount(id: garment.garment.id, pct: UInt32(pct))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Slider(value: $pct, in: 5...70, step: 5)
                    Text("Rebaja de \(Int(pct))%").font(.headline)
                }
                if let p = preview {
                    Section {
                        AmountRow("Precio actual", p.oldPrice)
                        AmountRow("Nuevo precio", p.newPrice, bold: true)
                        AmountRow("Costo", p.cost)
                        AmountRow("Margen resultante (\(Fmt.pct(p.marginPct)))", p.margin, color: p.belowCost ? Palette.liquidar : Palette.olive)
                        if p.belowCost {
                            Label("Con esta rebaja la prenda queda bajo su costo.", systemImage: "exclamationmark.triangle.fill")
                                .foregroundStyle(Palette.liquidar).font(.subheadline.weight(.bold))
                        }
                    }
                }
            }
            .navigationTitle("Aplicar rebaja")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Aplicar") {
                        let value = UInt32(pct)
                        model.mutate("Rebaja del \(value)% aplicada") {
                            try $0.applyDiscount(id: garment.garment.id, pct: value, today: Fmt.today())
                        }
                        dismiss()
                    }
                }
            }
            .onAppear {
                let suggested = Double(garment.suggestedDiscountPct)
                pct = suggested > 0 ? suggested : 10
            }
        }
        .presentationDetents([.medium, .large])
    }
}

struct SellSheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let garment: Garment
    @State private var price = ""
    @State private var date = Fmt.today()
    @State private var method: PaymentMethod = .debit
    @State private var document: DocumentType = .boleta
    @State private var channel: Category = .ventaTienda

    var body: some View {
        NavigationStack {
            Form {
                MoneyField("Precio (con IVA)", text: $price)
                NaiveDatePicker("Fecha", date: $date)
                Picker("Medio de pago", selection: $method) { ForEach(PaymentMethod.all, id: \.self) { Text($0.label).tag($0) } }
                Picker("Documento", selection: $document) {
                    ForEach(DocumentType.options(for: .income), id: \.self) { Text($0.label).tag($0) }
                }
                Picker("Canal", selection: $channel) { ForEach(Category.income, id: \.self) { Text($0.label).tag($0) } }
            }
            .navigationTitle("Registrar venta")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Vender") {
                        guard let value = Int64(price) else { return }
                        let sale = SaleInput(garmentId: garment.id, price: value, date: date, paymentMethod: method,
                                             documentType: document, category: channel)
                        model.mutate("¡Venta registrada! Se creó el ingreso con su IVA.") { try $0.sellGarment(sale: sale) }
                        dismiss()
                    }
                }
            }
            .onAppear { price = String(garment.price) }
        }
    }
}

// MARK: - Formulario de prenda

struct GarmentFormView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let garmentId: Int64
    @State private var loaded = false
    @State private var suppliers: [Supplier] = []
    @State private var lots: [Lot] = []
    @State private var type: GarmentType = .basicoPolera
    @State private var brand = ""
    @State private var era: Era = .noventas
    @State private var size = ""
    @State private var color = ""
    @State private var material = ""
    @State private var condition: Condition = .muyBueno
    @State private var season: Season = .todoElAno
    @State private var cost = ""
    @State private var price = ""
    @State private var supplierId: Int64?
    @State private var lotId: Int64?
    @State private var intake = Fmt.today()
    @State private var location: StoreLocation = .percha
    @State private var photos: [String] = []
    @State private var descriptionText = ""

    var body: some View {
        Form {
            Section("Fotos") { PhotoStrip(paths: $photos) }
            Section("Prenda") {
                Picker("Tipo", selection: $type) { ForEach(GarmentType.all, id: \.self) { Text($0.label).tag($0) } }
                TextField("Marca", text: $brand)
                Picker("Época", selection: $era) { ForEach(Era.all, id: \.self) { Text($0.label).tag($0) } }
                TextField("Talla", text: $size)
                TextField("Color", text: $color)
                TextField("Material", text: $material)
                Picker("Conservación", selection: $condition) { ForEach(Condition.all, id: \.self) { Text($0.label).tag($0) } }
                Picker("Temporada", selection: $season) { ForEach(Season.all, id: \.self) { Text($0.label).tag($0) } }
            }
            Section("Precio") {
                MoneyField("Costo", text: $cost)
                MoneyField("Precio de venta", text: $price)
                if lotId != nil {
                    Text("El costo se calcula prorrateando el costo del lote.").font(.caption).foregroundStyle(.secondary)
                }
            }
            Section("Origen y ubicación") {
                Picker("Proveedor", selection: $supplierId) {
                    Text("Sin proveedor").tag(Int64?.none)
                    ForEach(suppliers, id: \.id) { Text($0.name).tag(Int64?.some($0.id)) }
                }
                Picker("Lote / fardo", selection: $lotId) {
                    Text("Sin lote").tag(Int64?.none)
                    ForEach(lots, id: \.id) { Text($0.name).tag(Int64?.some($0.id)) }
                }
                NaiveDatePicker("Fecha de ingreso", date: $intake)
                Picker("Ubicación", selection: $location) { ForEach(StoreLocation.all, id: \.self) { Text($0.label).tag($0) } }
                TextField("Descripción / detalles", text: $descriptionText, axis: .vertical)
            }
            Section { Button("Guardar prenda") { save() }.frame(maxWidth: .infinity).fontWeight(.semibold) }
        }
        .navigationTitle(garmentId == 0 ? "Agregar prenda" : "Editar prenda")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear(perform: load)
        .vintageScreen()
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        suppliers = model.query { try $0.listSuppliers() } ?? []
        lots = model.query { try $0.listLots() } ?? []
        guard garmentId != 0, let g = model.query({ try $0.getGarment(id: garmentId, today: Fmt.today()).garment }) else { return }
        type = g.garmentType; brand = g.brand; era = g.era; size = g.size; color = g.color; material = g.material
        condition = g.condition; season = g.season; cost = String(g.cost); price = String(g.price)
        supplierId = g.supplierId; lotId = g.lotId; intake = g.intakeDate; location = g.location
        photos = g.photoPaths; descriptionText = g.description
    }

    private func save() {
        guard let p = Int64(price) else { model.message = "Ingresa el precio de venta."; return }
        let input = GarmentInput(garmentType: type, brand: brand, era: era, size: size, color: color, material: material,
                                 condition: condition, season: season, cost: Int64(cost) ?? 0, price: p,
                                 supplierId: supplierId, lotId: lotId, intakeDate: intake, location: location,
                                 photoPaths: photos, description: descriptionText)
        let ok: Bool
        if garmentId == 0 {
            ok = model.mutate("Prenda agregada") { try $0.addGarment(input: input, today: Fmt.today()) } != nil
        } else {
            ok = model.mutate("Cambios guardados") { try $0.updateGarment(id: garmentId, input: input, today: Fmt.today()) } != nil
        }
        if ok { dismiss() }
    }
}

// MARK: - Lotes

struct LotsView: View {
    @EnvironmentObject var model: AppModel
    @State private var lots: [Lot] = []
    @State private var suppliers: [Supplier] = []
    @State private var adding = false

    var body: some View {
        List {
            Text("El costo total de cada lote se reparte entre sus prendas, en proporción a su precio de venta.")
                .font(.caption).foregroundStyle(.secondary)
            if lots.isEmpty {
                EmptyStateView(icon: "shippingbox", title: "Sin lotes registrados",
                               message: "Registra tus fardos o compras a particulares para conocer el costo real de cada prenda.")
                    .listRowBackground(Color.clear)
            }
            ForEach(lots, id: \.id) { l in
                VStack(alignment: .leading, spacing: 4) {
                    Text(l.name).font(.headline)
                    AmountRow("Costo total", l.totalCost)
                    InfoRow("Prendas", "\(l.garmentCount)")
                    if l.garmentCount > 0 { AmountRow("Costo promedio por prenda", l.totalCost / Int64(l.garmentCount)) }
                    InfoRow("Compra", "\(Fmt.cl(l.purchaseDate)) · \(l.documentType.label)")
                    if let s = suppliers.first(where: { $0.id == l.supplierId }) { InfoRow("Proveedor", s.name) }
                }
            }
            .onDelete { idx in
                for i in idx { let id = lots[i].id; model.mutate("Lote eliminado") { try $0.deleteLot(id: id) } }
            }
            .listRowBackground(Palette.card)
        }
        .navigationTitle("Lotes y fardos")
        .toolbar { Button { adding = true } label: { Image(systemName: "plus") } }
        .task(id: model.version) {
            lots = model.query { try $0.listLots() } ?? []
            suppliers = model.query { try $0.listSuppliers() } ?? []
        }
        .sheet(isPresented: $adding) { LotForm(suppliers: suppliers).environmentObject(model) }
        .vintageScreen()
    }
}

struct LotForm: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let suppliers: [Supplier]
    @State private var name = ""
    @State private var supplierId: Int64?
    @State private var date = Fmt.today()
    @State private var total = ""
    @State private var document: DocumentType = .sinDocumento
    @State private var registerExpense = true
    @State private var method: PaymentMethod = .transfer

    var body: some View {
        NavigationStack {
            Form {
                TextField("Nombre (ej. Fardo invierno USA)", text: $name)
                Picker("Proveedor", selection: $supplierId) {
                    Text("Particular / sin ficha").tag(Int64?.none)
                    ForEach(suppliers, id: \.id) { Text($0.name).tag(Int64?.some($0.id)) }
                }
                NaiveDatePicker("Fecha de compra", date: $date)
                MoneyField("Costo total", text: $total)
                Picker("Documento", selection: $document) { ForEach(DocumentType.all, id: \.self) { Text($0.label).tag($0) } }
                Toggle("Registrar el egreso en Finanzas", isOn: $registerExpense)
                if registerExpense {
                    Picker("Medio de pago", selection: $method) { ForEach(PaymentMethod.all, id: \.self) { Text($0.label).tag($0) } }
                }
            }
            .navigationTitle("Nuevo lote / fardo")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Guardar") {
                        let input = LotInput(name: name, supplierId: supplierId, purchaseDate: date, totalCost: Int64(total) ?? 0,
                                             documentType: document, notes: "", registerExpense: registerExpense, paymentMethod: method)
                        if model.mutate("Lote registrado. Asigna prendas al lote para prorratear su costo.", { try $0.addLot(input: input) }) != nil {
                            dismiss()
                        }
                    }
                    .disabled(name.isEmpty || Int64(total) == nil)
                }
            }
        }
    }
}

// MARK: - Métricas

struct MetricsView: View {
    @EnvironmentObject var model: AppModel
    @State private var metrics: InventoryMetrics?

    var body: some View {
        List {
            if let m = metrics {
                Section("Margen promedio de ventas") {
                    AmountRow("Margen promedio por prenda", m.avgMargin)
                    InfoRow("Margen sobre precio neto", Fmt.pct(m.avgMarginPct))
                }
                Section("Días promedio de venta por tipo") {
                    let sold = m.byType.filter { $0.sold > 0 }
                    if sold.isEmpty { Text("Aún no hay ventas de prendas registradas.") }
                    ForEach(sold, id: \.garmentType) { t in
                        InfoRow(t.garmentType.label, "\(t.avgDaysToSell) días · \(t.sold) vendidas")
                    }
                }
                Section("Rotación por época") {
                    ForEach(m.byEra.filter { $0.sold + $0.inStock > 0 }, id: \.era) { e in
                        VStack(alignment: .leading) {
                            InfoRow(e.era.label, "\(Fmt.pct(e.rotationPct)) · \(e.sold) vendidas / \(e.inStock) en tienda")
                            ProgressView(value: Double(e.rotationPct) ?? 0).tint(Palette.olive)
                        }
                    }
                }
                Section("Prendas con más tiempo en tienda") {
                    ForEach(m.oldest, id: \.garment.id) { v in
                        NavigationLink(value: Route.garment(v.garment.id)) {
                            VStack(alignment: .leading) {
                                Text("\(v.garment.garmentType.label) · \(v.garment.brand)").font(.subheadline)
                                Text("Ingresó el \(Fmt.cl(v.garment.intakeDate)) · \(Fmt.clp(v.garment.price))").font(.caption)
                            }
                        }
                    }
                }
            } else {
                ProgressView()
            }
        }
        .listRowBackground(Palette.card)
        .navigationTitle("Métricas")
        .task(id: model.version) { metrics = model.query { try $0.inventoryMetrics(today: Fmt.today()) } }
        .vintageScreen()
    }
}
