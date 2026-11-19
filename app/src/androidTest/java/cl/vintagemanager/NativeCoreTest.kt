package cl.vintagemanager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cl.vintagemanager.core.Category
import cl.vintagemanager.core.CoreException
import cl.vintagemanager.core.DocumentType
import cl.vintagemanager.core.MovementInput
import cl.vintagemanager.core.MovementKind
import cl.vintagemanager.core.PaymentMethod
import cl.vintagemanager.core.VintageCore
import cl.vintagemanager.core.coreVersion
import cl.vintagemanager.core.formatClp
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Verifica que libvintage_core.so carga en el dispositivo y responde vía UniFFI. */
@RunWith(AndroidJUnit4::class)
class NativeCoreTest {

    private fun newCore(): VintageCore {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = File(ctx.cacheDir, "test_${System.nanoTime()}.db")
        return VintageCore(db.absolutePath)
    }

    @Test
    fun nativeLibraryLoadsAndResponds() {
        assertTrue(coreVersion().isNotBlank())
        assertEquals("$ 1.250.000", formatClp(1_250_000L))
    }

    @Test
    fun saleWithIvaUpdatesDebitAndPpm() {
        val core = newCore()
        val today = LocalDate.of(2026, 11, 19)
        core.addMovement(
            MovementInput(
                kind = MovementKind.INCOME, date = today, amount = 119_000L, includesIva = true,
                category = Category.VENTA_TIENDA, paymentMethod = PaymentMethod.DEBIT,
                documentType = DocumentType.BOLETA, documentNumber = null, description = "test",
                photoPath = null, garmentId = null,
            ),
        )
        val tax = core.monthlyTax(2026, 11u, today)
        assertEquals(19_000L, tax.ivaDebit)
        assertEquals(125L, tax.ppm)
        assertEquals(0, BigDecimal("0.19").compareTo(core.getSettings().ivaRate))
        core.close()
    }

    @Test
    fun coreErrorsArriveAsTypedExceptions() {
        val core = newCore()
        try {
            core.getMovement(999L)
            fail("debía lanzar excepción")
        } catch (e: CoreException.NotFound) {
            assertEquals(999L, e.id)
        }
        core.close()
    }

    @Test
    fun demoDataNeverExceedsThreePushes() {
        val core = newCore()
        val today = LocalDate.now()
        core.loadDemoData(today)
        var total = 0
        for (hour in 0 until 24) {
            total += core.evaluateAlerts(today, (hour * 60).toUInt()).notifications.size
        }
        assertTrue("se publicaron $total notificaciones", total <= 3)
        core.close()
    }
}
