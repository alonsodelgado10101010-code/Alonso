import SwiftUI
import UIKit

/// Paleta cálida vintage con variantes para modo oscuro.
enum Palette {
    static func dynamic(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) })
    }
    static let background = dynamic(0xF8EFE0, 0x221A15)
    static let card = dynamic(0xF3E7D3, 0x2C231D)
    static let terracotta = dynamic(0xA34A2E, 0xE59A7E)
    static let olive = dynamic(0x55632A, 0xB8C58A)
    static let coffee = dynamic(0x4A3426, 0xD9C2AE)
    static let fresca = Color(UIColor(hex: 0x3F7D3A))
    static let pronto = Color(UIColor(hex: 0xC99A1E))
    static let ya = Color(UIColor(hex: 0xD9732B))
    static let liquidar = Color(UIColor(hex: 0xB3261E))
}

extension UIColor {
    convenience init(hex: UInt32) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }
}

extension Font {
    /// Serif para títulos.
    static func vintageTitle(_ style: Font.TextStyle = .title2) -> Font { .system(style, design: .serif).weight(.semibold) }
}

/// Tarjeta con esquinas redondeadas.
struct CardView<Content: View>: View {
    var title: String?
    var trailing: AnyView?
    @ViewBuilder var content: () -> Content

    init(_ title: String? = nil, trailing: AnyView? = nil, @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.trailing = trailing
        self.content = content
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if title != nil || trailing != nil {
                HStack {
                    if let title { Text(title).font(.vintageTitle(.headline)) }
                    Spacer()
                    trailing
                }
            }
            content()
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.card, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

struct KpiCard: View {
    let label: String
    let amount: Int64
    let icon: String
    var accent: Color = Palette.terracotta
    var caption: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(label, systemImage: icon)
                .font(.caption.weight(.semibold))
                .foregroundStyle(accent)
            Text(Fmt.clp(amount))
                .font(.title3.weight(.bold))
                .foregroundStyle(amount < 0 ? Palette.liquidar : Color.primary)
                .minimumScaleFactor(0.6)
                .lineLimit(1)
            if let caption { Text(caption).font(.caption2).foregroundStyle(.secondary) }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.card, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

struct AmountRow: View {
    let label: String
    let amount: Int64
    var bold = false
    var color: Color?

    init(_ label: String, _ amount: Int64, bold: Bool = false, color: Color? = nil) {
        self.label = label
        self.amount = amount
        self.bold = bold
        self.color = color
    }

    var body: some View {
        HStack {
            Text(label).font(bold ? .subheadline.weight(.semibold) : .subheadline)
            Spacer()
            Text(Fmt.clp(amount))
                .font(bold ? .headline : .subheadline)
                .foregroundStyle(color ?? .primary)
        }
    }
}

struct InfoRow: View {
    let label: String
    let value: String
    init(_ label: String, _ value: String) { self.label = label; self.value = value }
    var body: some View {
        HStack(alignment: .top) {
            Text(label).font(.subheadline).foregroundStyle(.secondary)
            Spacer()
            Text(value).font(.subheadline).multilineTextAlignment(.trailing)
        }
    }
}

struct Chip: View {
    let text: String
    let color: Color
    var body: some View {
        Text(text)
            .font(.caption.weight(.semibold))
            .foregroundStyle(color)
            .padding(.horizontal, 10).padding(.vertical, 4)
            .background(color.opacity(0.16), in: Capsule())
    }
}

struct EmptyStateView: View {
    let icon: String
    let title: String
    let message: String
    var actionLabel: String?
    var action: (() -> Void)?

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: icon).font(.system(size: 48)).foregroundStyle(Palette.terracotta)
            Text(title).font(.vintageTitle(.headline)).multilineTextAlignment(.center)
            Text(message).font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
            if let actionLabel, let action {
                Button(actionLabel, action: action).buttonStyle(.borderedProminent).tint(Palette.terracotta)
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity)
    }
}

struct Disclaimer: View {
    var text = "Cálculo referencial. Confirma con tu contador antes de declarar en el SII."
    var body: some View {
        Label(text, systemImage: "info.circle").font(.caption).foregroundStyle(.secondary)
    }
}

/// Campo de monto en pesos: solo dígitos y vista previa formateada.
struct MoneyField: View {
    let title: String
    @Binding var text: String
    init(_ title: String, text: Binding<String>) { self.title = title; self._text = text }
    var body: some View {
        HStack {
            Text(title)
            Spacer()
            TextField("$ 0", text: Binding(get: { text }, set: { text = Fmt.digits($0) }))
                .keyboardType(.numberPad)
                .multilineTextAlignment(.trailing)
                .frame(maxWidth: 160)
        }
        if let value = Int64(text), value > 0 {
            Text(Fmt.clp(value)).font(.caption).foregroundStyle(.secondary)
        }
    }
}

/// Selector de fecha enlazado a una fecha del núcleo (texto ISO).
struct NaiveDatePicker: View {
    let title: String
    @Binding var date: NaiveDate
    init(_ title: String, date: Binding<NaiveDate>) { self.title = title; self._date = date }
    var body: some View {
        DatePicker(title, selection: Binding(get: { Fmt.date(date) }, set: { date = Fmt.naive($0) }),
                   displayedComponents: .date)
            .environment(\.locale, Fmt.locale)
    }
}

/// Fila de alerta (prioridad + título + detalle).
struct AlertRowView: View {
    let alert: Alert
    var body: some View {
        HStack(spacing: 10) {
            Circle().fill(alert.priority.color).frame(width: 10, height: 10)
            VStack(alignment: .leading, spacing: 2) {
                Text(alert.title).font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                if !alert.body.isEmpty {
                    Text(alert.body).font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer()
            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.secondary)
        }
        .contentShape(Rectangle())
    }
}

extension View {
    /// Fondo crema y aviso con los mensajes del modelo.
    func vintageScreen() -> some View {
        modifier(VintageScreenModifier())
    }
}

private struct VintageScreenModifier: ViewModifier {
    @EnvironmentObject var model: AppModel
    func body(content: Content) -> some View {
        content
            .scrollContentBackground(.hidden)
            .background(Palette.background.ignoresSafeArea())
            .alert(model.message ?? "", isPresented: Binding(
                get: { model.message != nil },
                set: { if !$0 { model.message = nil } }
            )) {
                Button("OK", role: .cancel) {}
            }
    }
}

/// Matriz QR del núcleo → imagen para mostrar e imprimir.
enum QRRenderer {
    static func image(payload: String, caption: [String], scale: CGFloat = 10) -> UIImage? {
        guard let m = try? qrMatrix(text: payload) else { return nil }
        let size = Int(m.size)
        let quiet = 4
        let side = CGFloat(size + quiet * 2) * scale
        let textHeight = CGFloat(caption.count) * 4 * scale
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: side, height: side + textHeight))
        return renderer.image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: side, height: side + textHeight))
            UIColor.black.setFill()
            for y in 0..<size {
                for x in 0..<size where m.modules[y * size + x] {
                    ctx.fill(CGRect(x: CGFloat(x + quiet) * scale, y: CGFloat(y + quiet) * scale, width: scale, height: scale))
                }
            }
            let style = NSMutableParagraphStyle()
            style.alignment = .center
            let attrs: [NSAttributedString.Key: Any] = [
                .font: UIFont.boldSystemFont(ofSize: 3 * scale), .foregroundColor: UIColor.black, .paragraphStyle: style,
            ]
            for (i, line) in caption.enumerated() {
                (line as NSString).draw(in: CGRect(x: 0, y: side + CGFloat(i) * 4 * scale - scale, width: side, height: 4 * scale),
                                        withAttributes: attrs)
            }
        }
    }
}
