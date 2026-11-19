//! Errores del núcleo. UniFFI los exporta a Kotlin como `CoreException.*`
//! (excepciones tipadas), así la UI puede mostrar mensajes claros.

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("Error de base de datos: {msg}")]
    Database { msg: String },
    #[error("No se encontró {entity} con id {id}")]
    NotFound { entity: String, id: i64 },
    #[error("Dato inválido: {msg}")]
    Validation { msg: String },
    #[error("Error de respaldo: {msg}")]
    Backup { msg: String },
}

pub type CoreResult<T> = Result<T, CoreError>;

impl CoreError {
    pub fn validation(msg: impl Into<String>) -> Self {
        CoreError::Validation { msg: msg.into() }
    }
    pub fn not_found(entity: &str, id: i64) -> Self {
        CoreError::NotFound { entity: entity.to_string(), id }
    }
}

impl From<rusqlite::Error> for CoreError {
    fn from(e: rusqlite::Error) -> Self {
        match e {
            rusqlite::Error::QueryReturnedNoRows => CoreError::NotFound {
                entity: "registro".into(),
                id: 0,
            },
            other => CoreError::Database { msg: other.to_string() },
        }
    }
}

impl From<rusqlite_migration::Error> for CoreError {
    fn from(e: rusqlite_migration::Error) -> Self {
        CoreError::Database { msg: format!("migración: {e}") }
    }
}

impl From<serde_json::Error> for CoreError {
    fn from(e: serde_json::Error) -> Self {
        CoreError::Backup { msg: e.to_string() }
    }
}
