import Foundation

enum Fmt {
    static let locale = Locale(identifier: "es_CL")

    private static let iso: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = .current
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    private static let display: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "es_CL")
        f.dateFormat = "dd-MM-yyyy"
        return f
    }()

    /// `$ 1.250.000` — CLP sin decimales, punto como separador de miles.
    static func clp(_ amount: Int64) -> String {
        let digits = String(amount.magnitude)
        var out = ""
        for (i, c) in digits.enumerated() {
            if i > 0 && (digits.count - i) % 3 == 0 { out.append(".") }
            out.append(c)
        }
        return amount < 0 ? "-$ \(out)" : "$ \(out)"
    }

    /// Fecha del núcleo (texto ISO) ↔ Date.
    static func date(_ naive: NaiveDate) -> Date { iso.date(from: naive) ?? Date() }
    static func naive(_ date: Date) -> NaiveDate { iso.string(from: date) }
    static func today() -> NaiveDate { naive(Date()) }
    static func addDays(_ naive: NaiveDate, _ days: Int) -> NaiveDate {
        self.naive(Calendar.current.date(byAdding: .day, value: days, to: date(naive)) ?? Date())
    }
    static func cl(_ naive: NaiveDate) -> String { display.string(from: date(naive)) }

    static func minuteOfDay() -> UInt32 {
        let c = Calendar.current.dateComponents([.hour, .minute], from: Date())
        return UInt32((c.hour ?? 0) * 60 + (c.minute ?? 0))
    }

    static func time(_ minutes: UInt32) -> String { String(format: "%02d:%02d", minutes / 60, minutes % 60) }

    static func days(_ d: Int64) -> String {
        switch d {
        case 0: return "hoy"
        case 1: return "mañana"
        case let x where x > 1: return "en \(x) días"
        case -1: return "hace 1 día"
        default: return "hace \(-d) días"
        }
    }

    /// Tasa fraccional del núcleo ("0.19") → "19%".
    static func pct(_ rate: Decimal) -> String { pctText(rate) + "%" }

    /// "0.00125" → "0,125" (sin %), para editar.
    static func pctText(_ rate: Decimal) -> String {
        guard let value = Foundation.Decimal(string: rate) else { return rate }
        let p = value * 100
        return NSDecimalNumber(decimal: p).stringValue.replacingOccurrences(of: ".", with: ",")
    }

    /// "19" o "0,125" → "0.19" / "0.00125" (texto decimal exacto para el núcleo).
    static func rate(fromPct text: String) -> Decimal? {
        let clean = text.replacingOccurrences(of: "%", with: "").replacingOccurrences(of: ",", with: ".")
            .trimmingCharacters(in: .whitespaces)
        guard let value = Foundation.Decimal(string: clean) else { return nil }
        return NSDecimalNumber(decimal: value / 100).stringValue
    }

    static func digits(_ text: String) -> String { String(text.filter(\.isNumber).prefix(12)) }

    static let monthNames = ["Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio",
                             "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"]
    static func month(_ m: UInt32) -> String { monthNames[max(0, min(11, Int(m) - 1))] }
}
