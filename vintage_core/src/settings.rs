//! Ajustes de la tienda. Todas las tasas tributarias son parámetros editables
//! (cambian por ley), nunca constantes en el código de cálculo.

use chrono::NaiveDate;
use rusqlite::{params, Connection, OptionalExtension};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::error::{CoreError, CoreResult};
use crate::models::{AlertKind, AlertPriority, Era, GarmentType, TaxRegime};

#[derive(Debug, Clone, PartialEq, uniffi::Record, Serialize, Deserialize)]
pub struct GarmentLifetime {
    pub garment_type: GarmentType,
    pub days: u32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    // Datos de la tienda
    pub store_name: String,
    pub store_rut: String,
    pub store_giro: String,

    // Tributario
    pub regime: TaxRegime,
    pub iva_rate: Decimal,
    pub idpc_rate: Decimal,
    /// Tasa PPM si los ingresos del año anterior ≤ umbral en UF.
    pub ppm_rate_low: Decimal,
    /// Tasa PPM si los ingresos del año anterior > umbral en UF.
    pub ppm_rate_high: Decimal,
    pub ppm_threshold_uf: i64,
    /// Valor UF de referencia en pesos.
    pub uf_value: i64,
    pub f29_due_day: u32,
    /// Caja con la que partió la tienda (para estimar caja disponible).
    pub initial_cash: i64,
    /// Remanente de crédito IVA previo al primer mes registrado.
    pub initial_iva_remanente: i64,

    // Inventario FEFO
    pub lifetimes: Vec<GarmentLifetime>,
    pub trending_eras: Vec<Era>,
    pub delicate_materials: Vec<String>,
    /// Fin de temporada Primavera-Verano (por defecto 20 de marzo).
    pub pv_end_month: u32,
    pub pv_end_day: u32,
    /// Fin de temporada Otoño-Invierno (por defecto 20 de septiembre).
    pub oi_end_month: u32,
    pub oi_end_day: u32,

    // Alertas
    pub daily_check_minute: u32,
    pub quiet_start_minute: u32,
    pub quiet_end_minute: u32,
    pub max_push_per_day: u32,
    pub push_threshold: AlertPriority,
    pub disabled_alert_kinds: Vec<AlertKind>,

    // Seguridad y respaldo
    pub lock_enabled: bool,
    pub biometric_enabled: bool,
    pub pin_hash: Option<String>,
    pub last_backup_date: Option<NaiveDate>,
}

impl Default for Settings {
    fn default() -> Self {
        let (idpc, low, high) = regime_defaults(TaxRegime::ProPymeGeneral);
        Settings {
            store_name: "Mi Tienda Vintage".into(),
            store_rut: String::new(),
            store_giro: "Venta al por menor de prendas de vestir usadas".into(),
            regime: TaxRegime::ProPymeGeneral,
            iva_rate: dec!(0.19),
            idpc_rate: idpc,
            ppm_rate_low: low,
            ppm_rate_high: high,
            ppm_threshold_uf: 50_000,
            uf_value: 39_700,
            f29_due_day: 20,
            initial_cash: 0,
            initial_iva_remanente: 0,
            lifetimes: default_lifetimes(),
            trending_eras: vec![],
            delicate_materials: ["seda", "lana", "cuero", "piel", "terciopelo"]
                .iter()
                .map(|s| s.to_string())
                .collect(),
            pv_end_month: 3,
            pv_end_day: 20,
            oi_end_month: 9,
            oi_end_day: 20,
            daily_check_minute: 9 * 60,
            quiet_start_minute: 21 * 60,
            quiet_end_minute: 8 * 60 + 30,
            max_push_per_day: 3,
            push_threshold: AlertPriority::High,
            disabled_alert_kinds: vec![],
            lock_enabled: false,
            biometric_enabled: false,
            pin_hash: None,
            last_backup_date: None,
        }
    }
}

/// Vidas comerciales base por tipo de prenda (días).
pub fn default_lifetimes() -> Vec<GarmentLifetime> {
    use GarmentType::*;
    [
        (BasicoPolera, 60),
        (JeansPantalon, 90),
        (VestidoFalda, 75),
        (ChaquetaAbrigo, 120),
        (Accesorio, 150),
        (Coleccion, 180),
    ]
    .into_iter()
    .map(|(garment_type, days)| GarmentLifetime { garment_type, days })
    .collect()
}

/// Tasas por defecto (año comercial 2026) según régimen: (IDPC, PPM bajo, PPM alto).
///
/// * Pro Pyme General (14 D N°3): IDPC 12,5% transitorio 2025–2027;
///   PPM 0,125% (≤ 50.000 UF) / 0,25% (> 50.000 UF), rebaja transitoria.
/// * Pro Pyme Transparente (14 D N°8): IDPC 0% (tributan los dueños); PPM 0,2% / 0,5%.
/// * Régimen General (14 A): IDPC 27%; PPM lo informa el SII (se usa 1% como punto de partida editable).
pub fn regime_defaults(regime: TaxRegime) -> (Decimal, Decimal, Decimal) {
    match regime {
        TaxRegime::ProPymeGeneral => (dec!(0.125), dec!(0.00125), dec!(0.0025)),
        TaxRegime::ProPymeTransparente => (dec!(0), dec!(0.002), dec!(0.005)),
        TaxRegime::RegimenGeneral => (dec!(0.27), dec!(0.01), dec!(0.01)),
    }
}

impl Settings {
    pub fn lifetime_days(&self, t: GarmentType) -> u32 {
        self.lifetimes
            .iter()
            .find(|l| l.garment_type == t)
            .map(|l| l.days)
            .unwrap_or_else(|| {
                default_lifetimes()
                    .into_iter()
                    .find(|l| l.garment_type == t)
                    .map(|l| l.days)
                    .unwrap_or(90)
            })
    }

    pub fn is_delicate(&self, material: &str) -> bool {
        let m = material.to_lowercase();
        self.delicate_materials
            .iter()
            .any(|d| !d.trim().is_empty() && m.contains(&d.trim().to_lowercase()))
    }

    pub fn alert_enabled(&self, kind: AlertKind) -> bool {
        !self.disabled_alert_kinds.contains(&kind)
    }

    pub fn validate(&self) -> CoreResult<()> {
        let one = Decimal::ONE;
        for (name, r) in [
            ("IVA", self.iva_rate),
            ("IDPC", self.idpc_rate),
            ("PPM", self.ppm_rate_low),
            ("PPM", self.ppm_rate_high),
        ] {
            if r < Decimal::ZERO || r >= one {
                return Err(CoreError::validation(format!(
                    "la tasa {name} debe estar entre 0% y 100%"
                )));
            }
        }
        if !(1..=28).contains(&self.f29_due_day) {
            return Err(CoreError::validation("el día de vencimiento F29 debe estar entre 1 y 28"));
        }
        if self.quiet_start_minute >= 1440 || self.quiet_end_minute >= 1440 || self.daily_check_minute >= 1440 {
            return Err(CoreError::validation("horario fuera de rango"));
        }
        if self.max_push_per_day == 0 {
            return Err(CoreError::validation("debe permitirse al menos 1 notificación diaria"));
        }
        if self.uf_value <= 0 {
            return Err(CoreError::validation("el valor UF debe ser positivo"));
        }
        Ok(())
    }
}

const SETTINGS_KEY: &str = "app_settings";

pub fn load(conn: &Connection) -> CoreResult<Settings> {
    let raw: Option<String> = conn
        .query_row(
            "SELECT value FROM settings WHERE key = ?1",
            params![SETTINGS_KEY],
            |r| r.get(0),
        )
        .optional()?;
    match raw {
        Some(json) => Ok(serde_json::from_str(&json).unwrap_or_default()),
        None => Ok(Settings::default()),
    }
}

pub fn save(conn: &Connection, s: &Settings) -> CoreResult<()> {
    s.validate()?;
    let json = serde_json::to_string(s)?;
    conn.execute(
        "INSERT INTO settings(key, value) VALUES (?1, ?2)
         ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        params![SETTINGS_KEY, json],
    )?;
    Ok(())
}

/// Cambia el régimen y aplica sus tasas por defecto (el usuario puede editarlas luego).
pub fn apply_regime(s: &mut Settings, regime: TaxRegime) {
    let (idpc, low, high) = regime_defaults(regime);
    s.regime = regime;
    s.idpc_rate = idpc;
    s.ppm_rate_low = low;
    s.ppm_rate_high = high;
}

pub fn hash_pin(pin: &str) -> String {
    let mut h = Sha256::new();
    h.update(b"vintage-manager::");
    h.update(pin.as_bytes());
    h.finalize().iter().map(|b| format!("{b:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_are_valid() {
        let s = Settings::default();
        s.validate().unwrap();
        assert_eq!(s.iva_rate, dec!(0.19));
        assert_eq!(s.lifetime_days(GarmentType::ChaquetaAbrigo), 120);
    }

    #[test]
    fn regime_switch_updates_rates() {
        let mut s = Settings::default();
        apply_regime(&mut s, TaxRegime::ProPymeTransparente);
        assert_eq!(s.idpc_rate, Decimal::ZERO);
        assert_eq!(s.ppm_rate_low, dec!(0.002));
        apply_regime(&mut s, TaxRegime::RegimenGeneral);
        assert_eq!(s.idpc_rate, dec!(0.27));
    }

    #[test]
    fn delicate_materials_match_case_insensitive() {
        let s = Settings::default();
        assert!(s.is_delicate("Seda natural"));
        assert!(s.is_delicate("CUERO"));
        assert!(!s.is_delicate("algodón"));
    }

    #[test]
    fn json_roundtrip_with_missing_fields_uses_defaults() {
        let s: Settings = serde_json::from_str(r#"{"store_name":"X"}"#).unwrap();
        assert_eq!(s.store_name, "X");
        assert_eq!(s.max_push_per_day, 3);
    }
}
