//! Tipos personalizados expuestos por UniFFI.
//!
//! * `NaiveDate` viaja como texto ISO-8601 (`2026-11-19`) y en Kotlin se
//!   convierte a `java.time.LocalDate` (ver `uniffi.toml`).
//! * `Decimal` viaja como texto (`0.19`) y en Kotlin es `java.math.BigDecimal`.
//!   Así las tasas nunca pasan por punto flotante.

use chrono::NaiveDate;
use rust_decimal::Decimal;
use std::str::FromStr;

uniffi::custom_type!(NaiveDate, String);

impl crate::UniffiCustomTypeConverter for NaiveDate {
    type Builtin = String;

    fn into_custom(val: Self::Builtin) -> uniffi::Result<Self> {
        Ok(NaiveDate::parse_from_str(&val, "%Y-%m-%d")?)
    }

    fn from_custom(obj: Self) -> Self::Builtin {
        obj.format("%Y-%m-%d").to_string()
    }
}

uniffi::custom_type!(Decimal, String);

impl crate::UniffiCustomTypeConverter for Decimal {
    type Builtin = String;

    fn into_custom(val: Self::Builtin) -> uniffi::Result<Self> {
        Ok(Decimal::from_str(val.trim())?)
    }

    fn from_custom(obj: Self) -> Self::Builtin {
        obj.normalize().to_string()
    }
}
