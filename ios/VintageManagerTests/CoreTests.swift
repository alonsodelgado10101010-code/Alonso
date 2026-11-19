import XCTest
@testable import VintageManager

/// Verifica que el núcleo Rust (XCFramework) enlaza y responde en iOS.
final class CoreTests: XCTestCase {
    private func newCore() throws -> VintageCore {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent("test_\(UUID().uuidString).db").path
        return try VintageCore(dbPath: path)
    }

    func testNativeCoreResponds() {
        XCTAssertFalse(coreVersion().isEmpty)
        XCTAssertEqual(formatClp(amount: 1_250_000), "$ 1.250.000")
    }

    func testSaleWithIvaUpdatesDebitAndPpm() throws {
        let core = try newCore()
        let today = "2026-11-19"
        _ = try core.addMovement(input: MovementInput(kind: .income, date: today, amount: 119_000, includesIva: true,
                                                      category: .ventaTienda, paymentMethod: .debit, documentType: .boleta,
                                                      documentNumber: nil, description: "test", photoPath: nil, garmentId: nil))
        let tax = try core.monthlyTax(year: 2026, month: 11, today: today)
        XCTAssertEqual(tax.ivaDebit, 19_000)
        XCTAssertEqual(tax.ppm, 125)
    }

    func testTypedErrors() throws {
        let core = try newCore()
        XCTAssertThrowsError(try core.getMovement(id: 999)) { error in
            guard case CoreError.NotFound(_, let id) = error else { return XCTFail("error inesperado: \(error)") }
            XCTAssertEqual(id, 999)
        }
    }

    func testDemoDataNeverExceedsThreePushes() throws {
        let core = try newCore()
        let today = Fmt.today()
        try core.loadDemoData(today: today)
        var total = 0
        for hour in 0..<24 {
            total += try core.evaluateAlerts(today: today, minuteOfDay: UInt32(hour * 60)).notifications.count
        }
        XCTAssertLessThanOrEqual(total, 3)
    }

    func testDataSurvivesReopening() throws {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent("persist_\(UUID().uuidString).db").path
        do {
            let core = try VintageCore(dbPath: path)
            _ = try core.addSupplier(input: SupplierInput(name: "Fardos", rut: "", contact: "", phone: "", email: ""))
        }
        let reopened = try VintageCore(dbPath: path)
        XCTAssertEqual(try reopened.listSuppliers().count, 1)
    }
}
