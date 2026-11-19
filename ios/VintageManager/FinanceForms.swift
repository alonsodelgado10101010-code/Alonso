import PhotosUI
import SwiftUI

// MARK: - Movimiento (nuevo / editar)

struct MovementFormView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let movementId: Int64
    let initialKind: MovementKind
    let garmentId: Int64?

    @State private var loaded = false
    @State private var kind: MovementKind = .income
    @State private var date = Fmt.today()
    @State private var amount = ""
    @State private var includesIva = true
    @State private var category: Category = .ventaTienda
    @State private var payment: PaymentMethod = .debit
    @State private var document: DocumentType = .boleta
    @State private var documentNumber = ""
    @State private var descriptionText = ""
    @State private var photoPath: String?
    @State private var garmentSku = ""
    @State private var confirmDelete = false

    private var preview: AmountBreakdown? {
        guard let value = Int64(amount), value > 0 else { return nil }
        return try? model.core.previewAmounts(amount: value, includesIva: includesIva, kind: kind, documentType: document)
    }

    var body: some View {
        Form {
            Section {
                Picker("Tipo", selection: $kind) {
                    ForEach(MovementKind.all, id: \.self) { Text($0.label).tag($0) }
                }
                .pickerStyle(.segmented)
                NaiveDatePicker("Fecha", date: $date)
                MoneyField("Monto", text: $amount)
                Toggle("¿El monto incluye IVA?", isOn: $includesIva)
                Picker("Categoría", selection: $category) {
                    ForEach(Category.options(for: kind), id: \.self) { Text($0.label).tag($0) }
                }
                Picker("Medio de pago", selection: $payment) {
                    ForEach(PaymentMethod.all, id: \.self) { Text($0.label).tag($0) }
                }
                Picker("Documento", selection: $document) {
                    ForEach(DocumentType.options(for: kind), id: \.self) { Text($0.label).tag($0) }
                }
                if document != .sinDocumento {
                    TextField("N° de documento", text: $documentNumber)
                }
                TextField("Descripción", text: $descriptionText, axis: .vertical)
                if kind == .income {
                    TextField("Prenda asociada (SKU, opcional)", text: $garmentSku)
                        .textInputAutocapitalization(.characters)
                }
            }
            Section("Foto del comprobante (opcional)") {
                PhotoStrip(paths: Binding(get: { photoPath.map { [$0] } ?? [] }, set: { photoPath = $0.last }), max: 1)
            }
            if let p = preview {
                Section("Desglose") {
                    AmountRow("Neto", p.net)
                    AmountRow("IVA", p.iva)
                    AmountRow("Total", p.gross, bold: true)
                    if kind == .expense && p.iva > 0 {
                        Text(p.ivaCreditable ? "Este IVA se usa como crédito fiscal en tu F29."
                                             : "Las boletas de compra no dan crédito fiscal.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            Section {
                Button("Guardar") { save() }.frame(maxWidth: .infinity).fontWeight(.semibold)
                if movementId != 0 {
                    Button("Eliminar movimiento", role: .destructive) { confirmDelete = true }.frame(maxWidth: .infinity)
                }
            }
        }
        .navigationTitle(movementId != 0 ? "Editar movimiento" : (kind == .income ? "Registrar ingreso" : "Registrar egreso"))
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: kind) { k in
            if !Category.options(for: k).contains(category) { category = Category.options(for: k)[0] }
            if !DocumentType.options(for: k).contains(document) { document = k == .income ? .boleta : .factura }
        }
        .onAppear(perform: load)
        .confirmationDialog("¿Eliminar movimiento?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Eliminar", role: .destructive) {
                if model.mutate("Movimiento eliminado", { try $0.deleteMovement(id: movementId) }) != nil { dismiss() }
            }
        } message: {
            Text("Si era la venta de una prenda, la prenda vuelve a stock.")
        }
        .vintageScreen()
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        if movementId == 0 {
            kind = initialKind
            category = Category.options(for: initialKind)[0]
            document = initialKind == .income ? .boleta : .factura
            if let gid = garmentId, let g = model.query({ try $0.getGarment(id: gid, today: Fmt.today()) }) {
                garmentSku = g.garment.sku
                amount = String(g.garment.price)
                descriptionText = "Venta \(g.garment.sku)"
            }
        } else if let m = model.query({ try $0.getMovement(id: movementId) }) {
            kind = m.kind
            date = m.date
            amount = String(m.includesIva ? m.gross : m.net)
            includesIva = m.includesIva
            category = m.category
            payment = m.paymentMethod
            document = m.documentType
            documentNumber = m.documentNumber ?? ""
            descriptionText = m.description
            photoPath = m.photoPath
            if let gid = m.garmentId { garmentSku = model.query({ try $0.getGarment(id: gid, today: Fmt.today()).garment.sku }) ?? "" }
        }
    }

    private func save() {
        guard let value = Int64(amount), value > 0 else { model.message = "Ingresa un monto mayor a cero."; return }
        var linkedGarment: Int64?
        if kind == .income && !garmentSku.trimmingCharacters(in: .whitespaces).isEmpty {
            guard let found = model.query({ try $0.findByCode(code: garmentSku, today: Fmt.today()) }), let g = found else {
                model.message = "No existe una prenda con SKU \(garmentSku)."
                return
            }
            linkedGarment = g.garment.id
        }
        let input = MovementInput(kind: kind, date: date, amount: value, includesIva: includesIva, category: category,
                                  paymentMethod: payment, documentType: document,
                                  documentNumber: documentNumber.isEmpty ? nil : documentNumber,
                                  description: descriptionText.trimmingCharacters(in: .whitespacesAndNewlines),
                                  photoPath: photoPath, garmentId: linkedGarment)
        let ok: Bool
        if movementId != 0 {
            ok = model.mutate("Cambios guardados") { try $0.updateMovement(id: movementId, input: input) } != nil
        } else if let gid = linkedGarment, includesIva {
            // Venta de una prenda: se descuenta del stock y se crea el ingreso con su IVA.
            let sale = SaleInput(garmentId: gid, price: value, date: date, paymentMethod: payment, documentType: document, category: category)
            ok = model.mutate("Venta registrada") { try $0.sellGarment(sale: sale) } != nil
        } else {
            ok = model.mutate("Movimiento guardado") { try $0.addMovement(input: input) } != nil
        }
        if ok { dismiss() }
    }
}

// MARK: - Abonos

struct PaymentSheet: View {
    @Environment(\.dismiss) private var dismiss
    let remaining: Int64
    let onConfirm: (Int64, NaiveDate, PaymentMethod) -> Void
    @State private var amount = ""
    @State private var date = Fmt.today()
    @State private var method: PaymentMethod = .transfer

    var body: some View {
        NavigationStack {
            Form {
                Text("Saldo pendiente: \(Fmt.clp(remaining))")
                MoneyField("Monto del abono", text: $amount)
                NaiveDatePicker("Fecha", date: $date)
                Picker("Medio de pago", selection: $method) {
                    ForEach(PaymentMethod.all, id: \.self) { Text($0.label).tag($0) }
                }
            }
            .navigationTitle("Registrar abono")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Registrar") {
                        if let v = Int64(amount) { onConfirm(v, date, method) }
                        dismiss()
                    }
                }
            }
            .onAppear { amount = String(remaining) }
        }
        .presentationDetents([.medium])
    }
}

struct DebtStatusSection: View {
    let amount: Int64
    let paid: Int64
    let remaining: Int64
    let status: DebtStatus
    let due: NaiveDate
    let days: Int64
    let payments: [DebtPayment]

    var body: some View {
        Section {
            HStack { Text("Estado"); Spacer(); Chip(text: status.label, color: status.color) }
            AmountRow("Monto total", amount)
            AmountRow("Abonado", paid)
            AmountRow("Saldo pendiente", remaining, bold: true)
            if status != .paid { Text("Vence el \(Fmt.cl(due)) (\(Fmt.days(days)))").font(.subheadline) }
        }
        if !payments.isEmpty {
            Section("Abonos registrados") {
                ForEach(payments, id: \.id) { p in AmountRow(Fmt.cl(p.date), p.amount) }
                Text("Cada abono generó automáticamente su movimiento en Finanzas.").font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}

// MARK: - Cobro

struct ReceivableView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let receivableId: Int64
    @State private var current: Receivable?
    @State private var loaded = false
    @State private var customer = ""
    @State private var amount = ""
    @State private var issue = Fmt.today()
    @State private var due = Fmt.addDays(Fmt.today(), 30)
    @State private var concept: ReceivableConcept = .ventaCredito
    @State private var notes = ""
    @State private var paying = false
    @State private var confirmDelete = false

    var body: some View {
        Form {
            if let r = current {
                DebtStatusSection(amount: r.amount, paid: r.paidAmount, remaining: r.remaining, status: r.status,
                                  due: r.dueDate, days: r.daysToDue, payments: r.payments)
                if r.status != .paid {
                    Section {
                        Button("Registrar abono") { paying = true }
                        Button("Marcar como pagado") {
                            model.mutate("Cobro pagado. Se creó el ingreso.") {
                                try $0.markReceivablePaid(id: receivableId, date: Fmt.today(), method: .transfer)
                            }
                        }
                    }
                }
            }
            Section("Datos del cobro") {
                TextField("Cliente", text: $customer)
                MoneyField("Monto (con IVA)", text: $amount)
                Picker("Concepto", selection: $concept) {
                    ForEach(ReceivableConcept.all, id: \.self) { Text($0.label).tag($0) }
                }
                NaiveDatePicker("Emisión", date: $issue)
                NaiveDatePicker("Vencimiento", date: $due)
                TextField("Notas", text: $notes, axis: .vertical)
            }
            Section {
                Button("Guardar") { save() }.frame(maxWidth: .infinity).fontWeight(.semibold)
                if receivableId != 0 {
                    Button("Eliminar cobro", role: .destructive) { confirmDelete = true }.frame(maxWidth: .infinity)
                }
            }
        }
        .navigationTitle(receivableId == 0 ? "Nuevo cobro" : "Cobro")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: model.version) { load() }
        .sheet(isPresented: $paying) {
            PaymentSheet(remaining: current?.remaining ?? 0) { value, date, method in
                model.mutate("Abono registrado e ingreso creado") {
                    try $0.payReceivable(id: receivableId, amount: value, date: date, method: method)
                }
            }
        }
        .confirmationDialog("¿Eliminar cobro?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Eliminar", role: .destructive) {
                if model.mutate("Cobro eliminado", { try $0.deleteReceivable(id: receivableId) }) != nil { dismiss() }
            }
        }
        .vintageScreen()
    }

    private func load() {
        guard receivableId != 0 else { return }
        current = model.query { try $0.getReceivable(id: receivableId, today: Fmt.today()) }
        if !loaded, let r = current {
            loaded = true
            customer = r.customer; amount = String(r.amount); issue = r.issueDate; due = r.dueDate
            concept = r.concept; notes = r.notes
        }
    }

    private func save() {
        guard let value = Int64(amount) else { model.message = "Ingresa el monto."; return }
        let input = ReceivableInput(customer: customer, amount: value, issueDate: issue, dueDate: due, concept: concept, notes: notes)
        if receivableId == 0 {
            if model.mutate("Cobro guardado", { try $0.addReceivable(input: input, today: Fmt.today()) }) != nil { dismiss() }
        } else {
            model.mutate("Cambios guardados") { try $0.updateReceivable(id: receivableId, input: input, today: Fmt.today()) }
        }
    }
}

// MARK: - Pago a proveedor

struct PayableView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let payableId: Int64
    @State private var current: Payable?
    @State private var suppliers: [Supplier] = []
    @State private var loaded = false
    @State private var supplierId: Int64?
    @State private var supplierName = ""
    @State private var amount = ""
    @State private var issue = Fmt.today()
    @State private var due = Fmt.addDays(Fmt.today(), 30)
    @State private var document: DocumentType = .factura
    @State private var documentNumber = ""
    @State private var category: Category = .compraMercaderia
    @State private var notes = ""
    @State private var paying = false
    @State private var confirmDelete = false

    var body: some View {
        Form {
            if let p = current {
                DebtStatusSection(amount: p.amount, paid: p.paidAmount, remaining: p.remaining, status: p.status,
                                  due: p.dueDate, days: p.daysToDue, payments: p.payments)
                if p.status != .paid {
                    Section {
                        Button("Registrar abono") { paying = true }
                        Button("Marcar como pagado") {
                            model.mutate("Pago registrado. Se creó el egreso.") {
                                try $0.markPayablePaid(id: payableId, date: Fmt.today(), method: .transfer)
                            }
                        }
                    }
                }
            }
            Section("Datos del pago") {
                Picker("Proveedor", selection: $supplierId) {
                    Text("Otro (escribir nombre)").tag(Int64?.none)
                    ForEach(suppliers, id: \.id) { Text($0.name).tag(Int64?.some($0.id)) }
                }
                if supplierId == nil { TextField("Nombre del proveedor", text: $supplierName) }
                NavigationLink(value: Route.suppliers) { Text("Fichas de proveedores").font(.footnote) }
                MoneyField("Monto total (con IVA)", text: $amount)
                Picker("Categoría", selection: $category) {
                    ForEach(Category.expense, id: \.self) { Text($0.label).tag($0) }
                }
                Picker("Documento", selection: $document) {
                    ForEach(DocumentType.all, id: \.self) { Text($0.label).tag($0) }
                }
                TextField("N° de documento", text: $documentNumber)
                NaiveDatePicker("Emisión", date: $issue)
                NaiveDatePicker("Vencimiento", date: $due)
                TextField("Notas", text: $notes, axis: .vertical)
            }
            Section {
                Button("Guardar") { save() }.frame(maxWidth: .infinity).fontWeight(.semibold)
                if payableId != 0 {
                    Button("Eliminar pago", role: .destructive) { confirmDelete = true }.frame(maxWidth: .infinity)
                }
            }
        }
        .navigationTitle(payableId == 0 ? "Nuevo pago a proveedor" : "Pago a proveedor")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: model.version) { load() }
        .sheet(isPresented: $paying) {
            PaymentSheet(remaining: current?.remaining ?? 0) { value, date, method in
                model.mutate("Abono registrado y egreso creado") {
                    try $0.payPayable(id: payableId, amount: value, date: date, method: method)
                }
            }
        }
        .confirmationDialog("¿Eliminar pago pendiente?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Eliminar", role: .destructive) {
                if model.mutate("Pago eliminado", { try $0.deletePayable(id: payableId) }) != nil { dismiss() }
            }
        }
        .vintageScreen()
    }

    private func load() {
        suppliers = model.query { try $0.listSuppliers() } ?? []
        guard payableId != 0 else { return }
        current = model.query { try $0.getPayable(id: payableId, today: Fmt.today()) }
        if !loaded, let p = current {
            loaded = true
            supplierId = p.supplierId; supplierName = p.supplierName; amount = String(p.amount)
            issue = p.issueDate; due = p.dueDate; document = p.documentType
            documentNumber = p.documentNumber ?? ""; category = p.category; notes = p.notes
        }
    }

    private func save() {
        guard let value = Int64(amount) else { model.message = "Ingresa el monto."; return }
        let name = suppliers.first { $0.id == supplierId }?.name ?? supplierName
        let input = PayableInput(supplierId: supplierId, supplierName: name, amount: value, issueDate: issue, dueDate: due,
                                 documentType: document, documentNumber: documentNumber.isEmpty ? nil : documentNumber,
                                 category: category, notes: notes)
        if payableId == 0 {
            if model.mutate("Pago guardado", { try $0.addPayable(input: input, today: Fmt.today()) }) != nil { dismiss() }
        } else {
            model.mutate("Cambios guardados") { try $0.updatePayable(id: payableId, input: input, today: Fmt.today()) }
        }
    }
}

// MARK: - Proveedores

struct SuppliersView: View {
    @EnvironmentObject var model: AppModel
    @State private var suppliers: [Supplier] = []
    @State private var editing: Supplier?
    @State private var creating = false

    var body: some View {
        List {
            if suppliers.isEmpty {
                EmptyStateView(icon: "person.2", title: "Aún no tienes proveedores",
                               message: "Guarda a quienes te venden fardos, lotes o servicios.")
                    .listRowBackground(Color.clear)
            }
            ForEach(suppliers, id: \.id) { s in
                Button { editing = s } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(s.name).font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                        let details = [s.rut, s.contact, s.phone, s.email].filter { !$0.isEmpty }
                        if !details.isEmpty { Text(details.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary) }
                    }
                }
            }
            .onDelete { idx in
                for i in idx { let id = suppliers[i].id; model.mutate("Proveedor eliminado") { try $0.deleteSupplier(id: id) } }
            }
            .listRowBackground(Palette.card)
        }
        .navigationTitle("Proveedores")
        .toolbar { Button { creating = true } label: { Image(systemName: "plus") } }
        .task(id: model.version) { suppliers = model.query { try $0.listSuppliers() } ?? [] }
        .sheet(isPresented: $creating) { SupplierForm(initial: nil).environmentObject(model) }
        .sheet(item: Binding(get: { editing.map { IdentifiedSupplier(s: $0) } }, set: { editing = $0?.s })) { SupplierForm(initial: $0.s).environmentObject(model) }
        .vintageScreen()
    }
}

struct IdentifiedSupplier: Identifiable {
    let s: Supplier
    var id: Int64 { s.id }
}

struct SupplierForm: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let initial: Supplier?
    @State private var name = ""
    @State private var rut = ""
    @State private var contact = ""
    @State private var phone = ""
    @State private var email = ""

    var body: some View {
        NavigationStack {
            Form {
                TextField("Nombre", text: $name)
                TextField("RUT (ej. 76.086.428-5)", text: $rut)
                if !rut.isEmpty && !isValidRut(rut: rut) { Text("RUT no válido").foregroundStyle(Palette.liquidar).font(.caption) }
                TextField("Contacto", text: $contact)
                TextField("Teléfono", text: $phone).keyboardType(.phonePad)
                TextField("Correo", text: $email).keyboardType(.emailAddress).textInputAutocapitalization(.never)
            }
            .navigationTitle(initial == nil ? "Nuevo proveedor" : "Editar proveedor")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Guardar") {
                        let input = SupplierInput(name: name, rut: rut, contact: contact, phone: phone, email: email)
                        let ok: Bool
                        if let s = initial {
                            ok = model.mutate("Proveedor actualizado") { try $0.updateSupplier(id: s.id, input: input) } != nil
                        } else {
                            ok = model.mutate("Proveedor agregado") { try $0.addSupplier(input: input) } != nil
                        }
                        if ok { dismiss() }
                    }
                    .disabled(name.isEmpty || (!rut.isEmpty && !isValidRut(rut: rut)))
                }
            }
            .onAppear {
                if let s = initial { name = s.name; rut = s.rut; contact = s.contact; phone = s.phone; email = s.email }
            }
        }
    }
}
