import SwiftUI
import VisionKit

struct InventoryView: View {
    @EnvironmentObject var model: AppModel
    @State private var query = ""
    @State private var type: GarmentType?
    @State private var season: Season?
    @State private var era: Era?
    @State private var size = ""
    @State private var showFilters = false
    @State private var items: [GarmentView] = []
    @State private var all: [GarmentView] = []
    @State private var scanning = false

    private var filter: InventoryFilter {
        InventoryFilter(semaphore: model.inventorySemaphore, garmentType: type, size: size.isEmpty ? nil : size,
                        season: season, era: era, query: query.isEmpty ? nil : query, includeInactive: false)
    }

    private var reloadKey: String {
        "\(model.version)|\(String(describing: model.inventorySemaphore))|\(String(describing: type))|\(String(describing: season))|\(String(describing: era))|\(size)|\(query)"
    }

    var body: some View {
        List {
            Section {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        filterChip("Todas (\(all.count))", selected: model.inventorySemaphore == nil) { model.inventorySemaphore = nil }
                        ForEach(Semaphore.all, id: \.self) { s in
                            let count = all.filter { $0.semaphore == s }.count
                            filterChip("\(s.emoji) \(s.label) (\(count))", selected: model.inventorySemaphore == s) {
                                model.inventorySemaphore = model.inventorySemaphore == s ? nil : s
                            }
                        }
                    }
                }
                DisclosureGroup("Más filtros", isExpanded: $showFilters) {
                    Picker("Tipo", selection: $type) {
                        Text("Todos").tag(GarmentType?.none)
                        ForEach(GarmentType.all, id: \.self) { Text($0.label).tag(GarmentType?.some($0)) }
                    }
                    Picker("Temporada", selection: $season) {
                        Text("Todas").tag(Season?.none)
                        ForEach(Season.all, id: \.self) { Text($0.label).tag(Season?.some($0)) }
                    }
                    Picker("Época", selection: $era) {
                        Text("Todas").tag(Era?.none)
                        ForEach(Era.all, id: \.self) { Text($0.label).tag(Era?.some($0)) }
                    }
                    TextField("Talla", text: $size)
                }
                Text("Ordenadas por Fecha Límite de Rotación: primero las que pierden valor antes.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            if items.isEmpty {
                EmptyStateView(icon: "tshirt",
                               title: all.isEmpty ? "Tu inventario está vacío" : "Ninguna prenda coincide",
                               message: all.isEmpty ? "Agrega tu primera prenda o carga los datos de demostración en Ajustes."
                                                    : "Prueba con otros filtros.")
                    .listRowBackground(Color.clear)
            }
            ForEach(items, id: \.garment.id) { v in
                NavigationLink(value: Route.garment(v.garment.id)) { GarmentRow(v: v) }
            }
            .listRowBackground(Palette.card)
        }
        .searchable(text: $query, prompt: "Marca, color, SKU…")
        .navigationTitle("Inventario")
        .toolbar {
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                Button { scanning = true } label: { Image(systemName: "qrcode.viewfinder") }
                Menu {
                    Button { model.inventoryPath.append(.lots) } label: { Label("Lotes y fardos", systemImage: "shippingbox") }
                    Button { model.inventoryPath.append(.metrics) } label: { Label("Métricas", systemImage: "chart.bar") }
                } label: { Image(systemName: "ellipsis.circle") }
                NavigationLink(value: Route.garmentEdit(0)) { Image(systemName: "plus") }
            }
        }
        .task(id: reloadKey) {
            // El orden por FLR lo garantiza el núcleo.
            items = model.query { try $0.listInventory(filter: filter, today: Fmt.today()) } ?? []
            all = model.query {
                try $0.listInventory(filter: InventoryFilter(semaphore: nil, garmentType: nil, size: nil, season: nil, era: nil,
                                                             query: nil, includeInactive: false), today: Fmt.today())
            } ?? []
        }
        .sheet(isPresented: $scanning) {
            QRScannerSheet { code in
                scanning = false
                if let found = model.query({ try $0.findByCode(code: code, today: Fmt.today()) }), let v = found {
                    model.inventoryPath.append(.garment(v.garment.id))
                } else {
                    model.message = "No se encontró una prenda con el código \(code)."
                }
            }
        }
        .vintageScreen()
    }

    private func filterChip(_ text: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text)
                .font(.footnote.weight(.semibold))
                .padding(.horizontal, 12).padding(.vertical, 6)
                .background(selected ? Palette.terracotta.opacity(0.2) : Palette.card, in: Capsule())
                .overlay(Capsule().stroke(selected ? Palette.terracotta : Color.secondary.opacity(0.3)))
        }
        .buttonStyle(.plain)
    }
}

struct GarmentRow: View {
    let v: GarmentView
    var body: some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 3).fill(v.semaphore.color).frame(width: 6)
            Group {
                if let path = v.garment.photoPaths.first, let img = PhotoStore.image(path) {
                    Image(uiImage: img).resizable().scaledToFill()
                } else {
                    Image(systemName: "tshirt").font(.title2).foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, maxHeight: .infinity).background(Color.secondary.opacity(0.12))
                }
            }
            .frame(width: 56, height: 56)
            .clipShape(RoundedRectangle(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 3) {
                Text("\(v.garment.garmentType.label) · \(v.garment.brand)").font(.subheadline.weight(.semibold)).lineLimit(1)
                Text([v.garment.era.label, "Talla \(v.garment.size)", v.garment.color, v.garment.sku].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                HStack(spacing: 6) {
                    Chip(text: v.semaphore.label, color: v.semaphore.color)
                    if v.garment.status != .enStock { Chip(text: v.garment.status.label, color: Palette.coffee) }
                }
                Text("FLR \(Fmt.cl(v.flr)) · " + (v.daysRemaining < 0 ? "vencida hace \(-v.daysRemaining) días" : "\(v.daysRemaining) días restantes"))
                    .font(.caption2)
            }
            Spacer()
            Text(Fmt.clp(v.garment.price)).font(.subheadline.weight(.bold))
        }
        .padding(.vertical, 4)
    }
}

/// Escáner de QR con VisionKit (iOS 16, iPhone XS o superior).
struct QRScannerSheet: View {
    let onCode: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var manual = ""

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                if DataScannerViewController.isSupported && DataScannerViewController.isAvailable {
                    QRScanner(onCode: onCode)
                        .clipShape(RoundedRectangle(cornerRadius: 16))
                        .frame(height: 360)
                    Text("Apunta al código QR de la etiqueta").font(.subheadline)
                } else {
                    Text("Este iPhone no puede escanear con la cámara. Escribe el SKU de la etiqueta.")
                        .multilineTextAlignment(.center)
                }
                HStack {
                    TextField("SKU (ej. VM-00001)", text: $manual)
                        .textFieldStyle(.roundedBorder)
                        .textInputAutocapitalization(.characters)
                    Button("Buscar") { onCode(manual) }.disabled(manual.isEmpty)
                }
                Spacer()
            }
            .padding()
            .navigationTitle("Escanear prenda")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cerrar") { dismiss() } } }
        }
    }
}

struct QRScanner: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(recognizedDataTypes: [.barcode(symbologies: [.qr])],
                                                qualityLevel: .balanced, isHighlightingEnabled: true)
        scanner.delegate = context.coordinator
        try? scanner.startScanning()
        return scanner
    }

    func updateUIViewController(_ controller: DataScannerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onCode: onCode) }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onCode: (String) -> Void
        private var done = false
        init(onCode: @escaping (String) -> Void) { self.onCode = onCode }

        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            guard !done else { return }
            for item in addedItems {
                if case .barcode(let code) = item, let value = code.payloadStringValue {
                    done = true
                    dataScanner.stopScanning()
                    onCode(value)
                    return
                }
            }
        }
    }
}
