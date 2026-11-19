//! Punto de extensión para sincronizar con la nube en el futuro.
//!
//! La app es *offline-first*: la fuente de verdad es la base SQLite local.
//! Cuando exista un backend (Supabase, Firebase, API propia…) bastará con
//! implementar este trait y conectarlo en `VintageCore`. No hay implementación
//! todavía, a propósito.

use chrono::NaiveDate;

use crate::error::CoreResult;

/// Cambio local pendiente de subir.
#[derive(Debug, Clone, PartialEq)]
pub struct ChangeSet {
    pub table: String,
    pub row_id: i64,
    /// Fila serializada en JSON (mismo formato que el respaldo).
    pub payload: String,
    pub deleted: bool,
}

pub trait SyncBackend: Send + Sync {
    /// Sube cambios locales. Devuelve cuántos fueron aceptados.
    fn push(&self, changes: &[ChangeSet]) -> CoreResult<usize>;
    /// Descarga cambios remotos posteriores a `since`.
    fn pull(&self, since: Option<NaiveDate>) -> CoreResult<Vec<ChangeSet>>;
    /// Identificador legible del backend (para Ajustes).
    fn name(&self) -> String;
}
