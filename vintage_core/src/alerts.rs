//! Motor de alertas con reglas anti-saturación.
//!
//! 1. `collect_alerts` evalúa todas las reglas y asigna prioridad (todas se ven
//!    dentro de la app, en "Requiere tu atención" y en la bandeja).
//! 2. `plan_push` (función pura) decide qué se publica como notificación push:
//!    * solo CRÍTICA y ALTA (según umbral configurable),
//!    * deduplicación de 24 h salvo que la prioridad suba,
//!    * horario silencioso (se difiere al terminar),
//!    * tope diario (3 por defecto): si se excede, todo se combina en un solo
//!      resumen tipo InboxStyle,
//!    * las alertas de inventario siempre son una sola (nunca una por prenda).
//! 3. `run_check` aplica el plan y lo registra en SQLite.

use std::collections::{HashMap, HashSet};

use chrono::{Datelike, Duration, NaiveDate};
use rusqlite::{params, Connection};

use crate::error::CoreResult;
use crate::finance;
use crate::inventory_fefo;
use crate::models::*;
use crate::reports::format_clp;
use crate::settings::Settings;
use crate::tax;

pub const SUMMARY_COMBINED_ID: i32 = 1000;
pub const SUMMARY_HIGH_ID: i32 = 1001;

fn alert(key: String, kind: AlertKind, priority: AlertPriority, title: String, body: String, target: AlertTarget) -> Alert {
    Alert { key, kind, priority, title, body, target }
}

fn days_text(days: i64) -> String {
    match days {
        0 => "hoy".to_string(),
        1 => "mañana".to_string(),
        d if d > 1 => format!("en {d} días"),
        -1 => "hace 1 día".to_string(),
        d => format!("hace {} días", -d),
    }
}

/// Evalúa todas las reglas. Excluye tipos desactivados y alertas pospuestas.
pub fn collect_alerts(conn: &Connection, s: &Settings, today: NaiveDate) -> CoreResult<Vec<Alert>> {
    let mut out: Vec<Alert> = Vec::new();
    let has_movements: bool =
        conn.query_row("SELECT EXISTS(SELECT 1 FROM movements)", [], |r| r.get(0))?;

    // ── F29 ──
    let mut f29_upcoming = 0i64;
    if has_movements {
        let (py, pm) = tax::pending_f29_period(today, s.f29_due_day);
        let mt = tax::monthly_tax(conn, s, py, pm, today)?;
        if !mt.filed {
            let days = mt.days_to_due;
            let priority = match days {
                d if d <= 3 => Some(AlertPriority::Critical),
                d if d <= 7 => Some(AlertPriority::Medium),
                _ => None,
            };
            if days <= 7 {
                f29_upcoming = mt.total_f29;
            }
            if let Some(p) = priority {
                out.push(alert(
                    format!("f29:{py}-{pm:02}"),
                    AlertKind::F29Due,
                    p,
                    format!("F29 de {} vence {}", month_name(pm), days_text(days)),
                    format!(
                        "Total estimado a pagar {} (IVA {} + PPM {}).",
                        format_clp(mt.total_f29),
                        format_clp(mt.iva_to_pay),
                        format_clp(mt.ppm)
                    ),
                    AlertTarget::Taxes,
                ));
            }
        }
    }

    // ── Pagos a proveedores ──
    let payables = finance::list_payables(conn, today, true)?;
    let mut upcoming_payments = 0i64;
    for p in &payables {
        if p.days_to_due <= 7 {
            upcoming_payments += p.remaining;
        }
        let (kind, priority) = match p.days_to_due {
            d if d < 0 => (AlertKind::SupplierPaymentOverdue, AlertPriority::Critical),
            d if d <= 2 => (AlertKind::SupplierPaymentDueSoon, AlertPriority::High),
            _ => continue,
        };
        let title = if p.days_to_due < 0 {
            format!("Pago vencido a {}", p.supplier_name)
        } else {
            format!("Pago a {} vence {}", p.supplier_name, days_text(p.days_to_due))
        };
        out.push(alert(
            format!("payable:{}", p.id),
            kind,
            priority,
            title,
            format!("Saldo {} · vencimiento {}", format_clp(p.remaining), p.due_date.format("%d-%m-%Y")),
            AlertTarget::Payable { id: p.id },
        ));
    }

    // ── Cobros ──
    for r in finance::list_receivables(conn, today, true)? {
        let (kind, priority, title) = match r.days_to_due {
            d if d < -15 => (AlertKind::ReceivableOverdue, AlertPriority::Critical, format!("Cobro a {} vencido {}", r.customer, days_text(d))),
            d if d < 0 => (AlertKind::ReceivableOverdue, AlertPriority::High, format!("Cobro a {} vencido {}", r.customer, days_text(d))),
            0 => (AlertKind::ReceivableDueToday, AlertPriority::High, format!("Cobro a {} vence hoy", r.customer)),
            d if d <= 7 => (AlertKind::ReceivableDueSoon, AlertPriority::Medium, format!("Cobro a {} vence {}", r.customer, days_text(d))),
            _ => continue,
        };
        out.push(alert(
            format!("receivable:{}", r.id),
            kind,
            priority,
            title,
            format!("Saldo por cobrar {}", format_clp(r.remaining)),
            AlertTarget::Receivable { id: r.id },
        ));
    }

    // ── Caja ──
    let cash = finance::cash_available(conn, s, today)?;
    let needed = upcoming_payments + f29_upcoming;
    if needed > 0 && cash - needed < 0 {
        out.push(alert(
            "cash:shortfall".into(),
            AlertKind::CashShortfall,
            AlertPriority::Critical,
            "La caja no alcanza para los próximos 7 días".into(),
            format!(
                "Caja estimada {} y pagos por {} (faltan {}).",
                format_clp(cash),
                format_clp(needed),
                format_clp(needed - cash)
            ),
            AlertTarget::Dashboard,
        ));
    }

    // ── Inventario (siempre agrupado) ──
    let views: Vec<GarmentView> = inventory_fefo::list_inventory(conn, s, &InventoryFilter::default(), today)?
        .into_iter()
        .filter(|v| v.garment.status != GarmentStatus::EnReparacion)
        .collect();
    let liquidar: Vec<&GarmentView> = views.iter().filter(|v| v.semaphore == Semaphore::Liquidar).collect();
    let new_liquidar = liquidar.iter().filter(|v| v.days_remaining >= -7).count();
    let rotar_ya = views.iter().filter(|v| v.semaphore == Semaphore::RotarYa).count();
    let rotar_pronto = views.iter().filter(|v| v.semaphore == Semaphore::RotarPronto).count();
    if new_liquidar >= 5 {
        out.push(alert(
            "inventory:liquidar".into(),
            AlertKind::InventoryLiquidate,
            AlertPriority::High,
            format!("{new_liquidar} prendas entraron en «Liquidar» esta semana"),
            format!("En total hay {} prendas para liquidar. Rebaja 40–50% o arma un outlet.", liquidar.len()),
            AlertTarget::Inventory { semaphore: Some(Semaphore::Liquidar) },
        ));
    } else if !liquidar.is_empty() {
        out.push(alert(
            "inventory:liquidar".into(),
            AlertKind::InventoryLiquidate,
            AlertPriority::Medium,
            format!("{} {} para liquidar", liquidar.len(), plural(liquidar.len(), "prenda", "prendas")),
            "Superaron su fecha límite de rotación.".into(),
            AlertTarget::Inventory { semaphore: Some(Semaphore::Liquidar) },
        ));
    }
    if rotar_ya > 0 {
        out.push(alert(
            "inventory:rotar_ya".into(),
            AlertKind::InventoryRotateNow,
            AlertPriority::Medium,
            format!("{rotar_ya} {} en «Rotar ya»", plural(rotar_ya, "prenda", "prendas")),
            "Sugerencia: rebaja del 20%.".into(),
            AlertTarget::Inventory { semaphore: Some(Semaphore::RotarYa) },
        ));
    }
    if rotar_pronto > 0 {
        out.push(alert(
            "inventory:rotar_pronto".into(),
            AlertKind::InventoryRotateSoon,
            AlertPriority::Low,
            format!("{rotar_pronto} {} en «Rotar pronto»", plural(rotar_pronto, "prenda", "prendas")),
            "Muévelas a vitrina o publícalas en redes.".into(),
            AlertTarget::Inventory { semaphore: Some(Semaphore::RotarPronto) },
        ));
    }
    let undiscounted = views
        .iter()
        .filter(|v| matches!(v.semaphore, Semaphore::RotarYa | Semaphore::Liquidar))
        .filter(|v| v.garment.last_discount_date.map_or(true, |d| (today - d).num_days() > 30))
        .count();
    if undiscounted > 0 {
        out.push(alert(
            "price:suggestion".into(),
            AlertKind::PriceSuggestion,
            AlertPriority::Low,
            format!("Sugerencia de precios para {undiscounted} {}", plural(undiscounted, "prenda", "prendas")),
            "Llevan más de 30 días sin rebaja y están por perder valor.".into(),
            AlertTarget::Inventory { semaphore: Some(Semaphore::RotarYa) },
        ));
    }

    // ── Respaldo ──
    let has_data = has_movements || !views.is_empty();
    let backup_due = s.last_backup_date.map_or(true, |d| (today - d).num_days() > 30);
    if has_data && backup_due {
        out.push(alert(
            "backup:reminder".into(),
            AlertKind::BackupReminder,
            AlertPriority::Low,
            "Respalda tus datos".into(),
            "Hace más de 30 días que no exportas un respaldo.".into(),
            AlertTarget::Settings,
        ));
    }

    // ── Filtros del usuario ──
    let snoozed = snoozed_keys(conn, today)?;
    out.retain(|a| s.alert_enabled(a.kind) && !snoozed.contains(&a.key));
    out.sort_by(|a, b| {
        a.priority
            .rank()
            .cmp(&b.priority.rank())
            .then(kind_order(a.kind).cmp(&kind_order(b.kind)))
            .then(a.key.cmp(&b.key))
    });
    Ok(out)
}

fn kind_order(k: AlertKind) -> usize {
    AlertKind::all().iter().position(|x| *x == k).unwrap_or(usize::MAX)
}

fn plural(n: usize, one: &'static str, many: &'static str) -> &'static str {
    if n == 1 {
        one
    } else {
        many
    }
}

pub fn month_name(m: u32) -> &'static str {
    [
        "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre",
        "noviembre", "diciembre",
    ]
    .get((m.max(1) - 1) as usize)
    .copied()
    .unwrap_or("")
}

// ───────────────────────────── plan de push ─────────────────────────────

/// Último envío conocido de una alerta: (minuto absoluto, prioridad).
pub type PushHistory = HashMap<String, (i64, AlertPriority)>;

pub fn abs_minute(date: NaiveDate, minute: u32) -> i64 {
    date.num_days_from_ce() as i64 * 1440 + minute as i64
}

pub fn in_quiet_hours(s: &Settings, minute: u32) -> bool {
    let (start, end) = (s.quiet_start_minute, s.quiet_end_minute);
    if start == end {
        false
    } else if start > end {
        minute >= start || minute < end
    } else {
        minute >= start && minute < end
    }
}

/// Id estable (FNV-1a) para notificaciones individuales, fuera del rango reservado.
pub fn stable_notification_id(key: &str) -> i32 {
    let mut h: u32 = 0x811c_9dc5;
    for b in key.as_bytes() {
        h ^= *b as u32;
        h = h.wrapping_mul(0x0100_0193);
    }
    (2000 + (h % 1_000_000_000)) as i32
}

/// Frase resumen tipo "3 pagos vencidos y 1 impuesto por vencer".
pub fn summary_phrase(alerts: &[&Alert]) -> String {
    let mut counts: Vec<(&'static str, &'static str, usize)> = Vec::new();
    for a in alerts {
        let (one, many) = match a.kind {
            AlertKind::SupplierPaymentOverdue => ("pago vencido", "pagos vencidos"),
            AlertKind::SupplierPaymentDueSoon => ("pago por vencer", "pagos por vencer"),
            AlertKind::F29Due => ("impuesto por vencer", "impuestos por vencer"),
            AlertKind::ReceivableOverdue => ("cobro vencido", "cobros vencidos"),
            AlertKind::ReceivableDueToday | AlertKind::ReceivableDueSoon => ("cobro por vencer", "cobros por vencer"),
            AlertKind::CashShortfall => ("alerta de caja", "alertas de caja"),
            _ => ("aviso de inventario", "avisos de inventario"),
        };
        match counts.iter_mut().find(|(o, _, _)| *o == one) {
            Some(entry) => entry.2 += 1,
            None => counts.push((one, many, 1)),
        }
    }
    let parts: Vec<String> = counts
        .iter()
        .map(|(one, many, n)| format!("{n} {}", if *n == 1 { one } else { many }))
        .collect();
    match parts.len() {
        0 => String::new(),
        1 => parts[0].clone(),
        n => format!("{} y {}", parts[..n - 1].join(", "), parts[n - 1]),
    }
}

fn single_notification(a: &Alert, channel: NotificationChannel) -> PushNotification {
    let (payable_id, receivable_id) = match a.target {
        AlertTarget::Payable { id } => (Some(id), None),
        AlertTarget::Receivable { id } => (None, Some(id)),
        _ => (None, None),
    };
    PushNotification {
        notification_id: stable_notification_id(&a.key),
        channel,
        priority: a.priority,
        title: a.title.clone(),
        text: a.body.clone(),
        lines: vec![],
        alert_keys: vec![a.key.clone()],
        target: a.target.clone(),
        payable_id,
        receivable_id,
    }
}

fn summary_notification(id: i32, title: String, items: &[&Alert], channel: NotificationChannel) -> PushNotification {
    let priority = items
        .iter()
        .map(|a| a.priority)
        .min_by_key(|p| p.rank())
        .unwrap_or(AlertPriority::High);
    PushNotification {
        notification_id: id,
        channel,
        priority,
        title,
        text: summary_phrase(items),
        lines: items.iter().map(|a| a.title.clone()).collect(),
        alert_keys: items.iter().map(|a| a.key.clone()).collect(),
        target: AlertTarget::Dashboard,
        payable_id: None,
        receivable_id: None,
    }
}

/// Decide qué notificaciones publicar. Función pura (testeable).
pub fn plan_push(
    alerts: &[Alert],
    history: &PushHistory,
    sent_today: u32,
    s: &Settings,
    today: NaiveDate,
    minute: u32,
) -> PushPlan {
    let threshold = s.push_threshold.rank().min(AlertPriority::High.rank());
    let now = abs_minute(today, minute);
    let eligible: Vec<&Alert> = alerts
        .iter()
        .filter(|a| a.priority.rank() <= threshold)
        .filter(|a| match history.get(&a.key) {
            // Dedup 24 h, salvo que la prioridad haya subido.
            Some((t, prev)) => now - t >= 24 * 60 || a.priority.rank() < prev.rank(),
            None => true,
        })
        .collect();

    if in_quiet_hours(s, minute) {
        return PushPlan {
            notifications: vec![],
            deferred_until_minute: if eligible.is_empty() { None } else { Some(s.quiet_end_minute) },
            sent_today,
            suppressed: 0,
        };
    }

    let critical: Vec<&Alert> = eligible.iter().copied().filter(|a| a.priority == AlertPriority::Critical).collect();
    let high: Vec<&Alert> = eligible.iter().copied().filter(|a| a.priority != AlertPriority::Critical).collect();

    let mut groups: Vec<PushNotification> = critical
        .iter()
        .map(|a| single_notification(a, NotificationChannel::Criticas))
        .collect();
    match high.len() {
        0 => {}
        1 => groups.push(single_notification(high[0], NotificationChannel::Resumen)),
        n => groups.push(summary_notification(
            SUMMARY_HIGH_ID,
            format!("Resumen del día: {n} avisos"),
            &high,
            NotificationChannel::Resumen,
        )),
    }

    let remaining = s.max_push_per_day.saturating_sub(sent_today) as usize;
    let total_alerts = eligible.len() as u32;
    let notifications = if groups.is_empty() || remaining == 0 {
        vec![]
    } else if groups.len() <= remaining {
        groups
    } else {
        let channel = if critical.is_empty() { NotificationChannel::Resumen } else { NotificationChannel::Criticas };
        vec![summary_notification(SUMMARY_COMBINED_ID, "Requiere tu atención".into(), &eligible, channel)]
    };
    let published_alerts: u32 = notifications.iter().map(|n| n.alert_keys.len() as u32).sum();
    PushPlan {
        sent_today: sent_today + notifications.len() as u32,
        suppressed: total_alerts - published_alerts,
        notifications,
        deferred_until_minute: None,
    }
}

// ───────────────────────────── persistencia ─────────────────────────────

pub fn load_history(conn: &Connection) -> CoreResult<PushHistory> {
    let mut stmt = conn.prepare(
        "SELECT key, last_sent_abs, last_priority FROM alert_state
         WHERE last_sent_abs IS NOT NULL AND last_priority IS NOT NULL",
    )?;
    let rows = stmt.query_map([], |r| {
        Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?, r.get::<_, AlertPriority>(2)?))
    })?;
    let mut h = HashMap::new();
    for row in rows {
        let (k, t, p) = row?;
        h.insert(k, (t, p));
    }
    Ok(h)
}

pub fn sent_count(conn: &Connection, today: NaiveDate) -> CoreResult<u32> {
    Ok(conn.query_row(
        "SELECT count(*) FROM notification_log WHERE sent_date = ?1",
        params![today],
        |r| r.get(0),
    )?)
}

fn snoozed_keys(conn: &Connection, today: NaiveDate) -> CoreResult<HashSet<String>> {
    let mut stmt = conn.prepare("SELECT key FROM alert_state WHERE snoozed_until IS NOT NULL AND snoozed_until > ?1")?;
    let rows = stmt.query_map(params![today], |r| r.get::<_, String>(0))?;
    Ok(rows.collect::<Result<_, _>>()?)
}

/// Pospone las alertas hasta `until` (exclusivo: vuelven ese día).
pub fn snooze(conn: &Connection, keys: &[String], until: NaiveDate) -> CoreResult<()> {
    for k in keys {
        conn.execute(
            "INSERT INTO alert_state(key, snoozed_until) VALUES (?1, ?2)
             ON CONFLICT(key) DO UPDATE SET snoozed_until = excluded.snoozed_until",
            params![k, until],
        )?;
    }
    Ok(())
}

pub fn record_plan(conn: &Connection, plan: &PushPlan, today: NaiveDate, minute: u32) -> CoreResult<()> {
    let tx = conn.unchecked_transaction()?;
    let now = abs_minute(today, minute);
    for n in &plan.notifications {
        tx.execute(
            "INSERT INTO notification_log (notification_id, alert_keys, priority, sent_date, sent_minute)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            params![n.notification_id, serde_json::to_string(&n.alert_keys)?, n.priority, today, minute],
        )?;
        for k in &n.alert_keys {
            tx.execute(
                "INSERT INTO alert_state(key, last_priority, last_sent_abs) VALUES (?1, ?2, ?3)
                 ON CONFLICT(key) DO UPDATE SET last_priority = excluded.last_priority,
                                                last_sent_abs = excluded.last_sent_abs",
                params![k, n.priority, now],
            )?;
        }
    }
    // Limpieza: el log solo se necesita por unos días.
    tx.execute(
        "DELETE FROM notification_log WHERE sent_date < ?1",
        params![today - Duration::days(14)],
    )?;
    tx.commit()?;
    Ok(())
}

/// Evalúa, planifica y registra. Es lo que llama el Worker diario.
pub fn run_check(conn: &Connection, s: &Settings, today: NaiveDate, minute: u32) -> CoreResult<PushPlan> {
    let alerts = collect_alerts(conn, s, today)?;
    let history = load_history(conn)?;
    let sent = sent_count(conn, today)?;
    let plan = plan_push(&alerts, &history, sent, s, today, minute);
    record_plan(conn, &plan, today, minute)?;
    Ok(plan)
}

/// Notificaciones publicadas cuya causa ya se resolvió (para retirarlas de la barra).
pub fn stale_notification_ids(conn: &Connection, s: &Settings, today: NaiveDate) -> CoreResult<Vec<i32>> {
    let active: HashSet<String> = collect_alerts(conn, s, today)?.into_iter().map(|a| a.key).collect();
    let mut stmt = conn.prepare(
        "SELECT notification_id, alert_keys FROM notification_log
         WHERE id IN (SELECT MAX(id) FROM notification_log WHERE sent_date >= ?1 GROUP BY notification_id)",
    )?;
    let rows = stmt.query_map(params![today - Duration::days(3)], |r| {
        Ok((r.get::<_, i32>(0)?, r.get::<_, String>(1)?))
    })?;
    let mut out = Vec::new();
    for row in rows {
        let (id, keys) = row?;
        let keys: Vec<String> = serde_json::from_str(&keys).unwrap_or_default();
        if !keys.iter().any(|k| active.contains(k)) {
            out.push(id);
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn d(y: i32, m: u32, day: u32) -> NaiveDate {
        NaiveDate::from_ymd_opt(y, m, day).unwrap()
    }

    fn a(key: &str, kind: AlertKind, p: AlertPriority) -> Alert {
        Alert {
            key: key.into(),
            kind,
            priority: p,
            title: key.into(),
            body: String::new(),
            target: AlertTarget::Dashboard,
        }
    }

    #[test]
    fn only_critical_and_high_are_pushed() {
        let s = Settings::default();
        let alerts = vec![
            a("m", AlertKind::InventoryRotateNow, AlertPriority::Medium),
            a("l", AlertKind::BackupReminder, AlertPriority::Low),
        ];
        let plan = plan_push(&alerts, &HashMap::new(), 0, &s, d(2026, 11, 19), 9 * 60);
        assert!(plan.notifications.is_empty());
        // Ni siquiera con umbral "Bajo" se publica algo bajo ALTA.
        let s = Settings { push_threshold: AlertPriority::Low, ..Settings::default() };
        let plan = plan_push(&alerts, &HashMap::new(), 0, &s, d(2026, 11, 19), 9 * 60);
        assert!(plan.notifications.is_empty());
    }

    #[test]
    fn threshold_critical_skips_high() {
        let s = Settings { push_threshold: AlertPriority::Critical, ..Settings::default() };
        let alerts = vec![a("h", AlertKind::SupplierPaymentDueSoon, AlertPriority::High)];
        assert!(plan_push(&alerts, &HashMap::new(), 0, &s, d(2026, 11, 19), 600).notifications.is_empty());
    }

    #[test]
    fn high_alerts_grouped_in_single_summary() {
        let s = Settings::default();
        let alerts = vec![
            a("c1", AlertKind::SupplierPaymentOverdue, AlertPriority::Critical),
            a("h1", AlertKind::SupplierPaymentDueSoon, AlertPriority::High),
            a("h2", AlertKind::ReceivableDueToday, AlertPriority::High),
            a("h3", AlertKind::InventoryLiquidate, AlertPriority::High),
        ];
        let plan = plan_push(&alerts, &HashMap::new(), 0, &s, d(2026, 11, 19), 600);
        assert_eq!(plan.notifications.len(), 2);
        assert_eq!(plan.notifications[0].channel, NotificationChannel::Criticas);
        let summary = &plan.notifications[1];
        assert_eq!(summary.notification_id, SUMMARY_HIGH_ID);
        assert_eq!(summary.channel, NotificationChannel::Resumen);
        assert_eq!(summary.lines.len(), 3);
    }

    #[test]
    fn daily_cap_of_three_combines_into_one_summary() {
        let s = Settings::default();
        let alerts = vec![
            a("p1", AlertKind::SupplierPaymentOverdue, AlertPriority::Critical),
            a("p2", AlertKind::SupplierPaymentOverdue, AlertPriority::Critical),
            a("p3", AlertKind::SupplierPaymentOverdue, AlertPriority::Critical),
            a("f29", AlertKind::F29Due, AlertPriority::Critical),
        ];
        let plan = plan_push(&alerts, &HashMap::new(), 0, &s, d(2026, 11, 19), 600);
        assert_eq!(plan.notifications.len(), 1);
        let n = &plan.notifications[0];
        assert_eq!(n.notification_id, SUMMARY_COMBINED_ID);
        assert_eq!(n.text, "3 pagos vencidos y 1 impuesto por vencer");
        assert_eq!(n.lines.len(), 4);
        assert_eq!(plan.sent_today, 1);
    }

    #[test]
    fn cap_counts_notifications_already_sent_today() {
        let s = Settings::default();
        let alerts = vec![
            a("p1", AlertKind::SupplierPaymentOverdue, AlertPriority::Critical),
            a("p2", AlertKind::SupplierPaymentOverdue, AlertPriority::Critical),
        ];
        // Ya se enviaron 2 hoy → queda 1: se combinan.
        let plan = plan_push(&alerts, &HashMap::new(), 2, &s, d(2026, 11, 19), 600);
        assert_eq!(plan.notifications.len(), 1);
        assert_eq!(plan.sent_today, 3);
        // Ya se enviaron 3 → nada más hoy.
        let plan = plan_push(&alerts, &HashMap::new(), 3, &s, d(2026, 11, 19), 600);
        assert!(plan.notifications.is_empty());
        assert_eq!(plan.suppressed, 2);
    }

    #[test]
    fn dedup_24h_unless_priority_rises() {
        let s = Settings::default();
        let today = d(2026, 11, 19);
        let mut history = PushHistory::new();
        history.insert("payable:1".into(), (abs_minute(today, 9 * 60) - 60, AlertPriority::High));
        // Misma prioridad hace 1 hora → no se repite.
        let alerts = vec![a("payable:1", AlertKind::SupplierPaymentDueSoon, AlertPriority::High)];
        assert!(plan_push(&alerts, &history, 0, &s, today, 9 * 60).notifications.is_empty());
        // Sube a CRÍTICA → sí se publica.
        let alerts = vec![a("payable:1", AlertKind::SupplierPaymentOverdue, AlertPriority::Critical)];
        assert_eq!(plan_push(&alerts, &history, 0, &s, today, 9 * 60).notifications.len(), 1);
        // Pasadas 24 h → se puede repetir.
        let alerts = vec![a("payable:1", AlertKind::SupplierPaymentDueSoon, AlertPriority::High)];
        let tomorrow = today + Duration::days(1);
        assert_eq!(plan_push(&alerts, &history, 0, &s, tomorrow, 9 * 60).notifications.len(), 1);
    }

    #[test]
    fn quiet_hours_defer() {
        let s = Settings::default();
        assert!(in_quiet_hours(&s, 22 * 60));
        assert!(in_quiet_hours(&s, 3 * 60));
        assert!(!in_quiet_hours(&s, 8 * 60 + 30));
        assert!(!in_quiet_hours(&s, 12 * 60));
        let alerts = vec![a("c", AlertKind::CashShortfall, AlertPriority::Critical)];
        let plan = plan_push(&alerts, &HashMap::new(), 0, &s, d(2026, 11, 19), 23 * 60);
        assert!(plan.notifications.is_empty());
        assert_eq!(plan.deferred_until_minute, Some(8 * 60 + 30));
    }

    #[test]
    fn summary_phrase_grammar() {
        let x = a("1", AlertKind::ReceivableOverdue, AlertPriority::Critical);
        let y = a("2", AlertKind::CashShortfall, AlertPriority::Critical);
        let z = a("3", AlertKind::F29Due, AlertPriority::Critical);
        assert_eq!(summary_phrase(&[&x]), "1 cobro vencido");
        assert_eq!(summary_phrase(&[&x, &y, &z]), "1 cobro vencido, 1 alerta de caja y 1 impuesto por vencer");
    }

    #[test]
    fn stable_ids_do_not_collide_with_summaries() {
        let id = stable_notification_id("payable:1");
        assert!(id >= 2000);
        assert_eq!(id, stable_notification_id("payable:1"));
        assert_ne!(id, stable_notification_id("payable:2"));
    }

    #[test]
    fn end_to_end_rules_and_resolution() {
        let conn = crate::db::open(":memory:").unwrap();
        let s = Settings { initial_cash: 1_000_000, ..Settings::default() };
        let today = d(2026, 11, 19);
        let pid = finance::add_payable(&conn, &PayableInput {
            supplier_id: None,
            supplier_name: "Fardos SpA".into(),
            amount: 50_000,
            issue_date: d(2026, 10, 1),
            due_date: d(2026, 11, 10),
            document_type: DocumentType::Factura,
            document_number: None,
            category: Category::CompraMercaderia,
            notes: String::new(),
        })
        .unwrap();
        let alerts = collect_alerts(&conn, &s, today).unwrap();
        let pa = alerts.iter().find(|a| a.key == format!("payable:{pid}")).unwrap();
        assert_eq!(pa.priority, AlertPriority::Critical);

        let plan = run_check(&conn, &s, today, 9 * 60).unwrap();
        assert_eq!(plan.notifications.len(), 1);
        let nid = plan.notifications[0].notification_id;
        assert_eq!(plan.notifications[0].payable_id, Some(pid));
        // Un segundo chequeo el mismo día no repite.
        assert!(run_check(&conn, &s, today, 10 * 60).unwrap().notifications.is_empty());
        assert!(stale_notification_ids(&conn, &s, today).unwrap().is_empty());
        // Al pagar, la notificación queda obsoleta y se retira.
        finance::pay_payable(&conn, &s, pid, 50_000, today, PaymentMethod::Transfer).unwrap();
        assert_eq!(stale_notification_ids(&conn, &s, today).unwrap(), vec![nid]);
    }

    #[test]
    fn snooze_hides_until_date_and_disabled_kinds_are_filtered() {
        let conn = crate::db::open(":memory:").unwrap();
        let mut s = Settings { initial_cash: 1_000_000, ..Settings::default() };
        let today = d(2026, 11, 19);
        let rid = finance::add_receivable(&conn, &ReceivableInput {
            customer: "Clienta".into(),
            amount: 10_000,
            issue_date: d(2026, 10, 1),
            due_date: d(2026, 10, 20),
            concept: ReceivableConcept::VentaCredito,
            notes: String::new(),
        })
        .unwrap();
        let key = format!("receivable:{rid}");
        let all = collect_alerts(&conn, &s, today).unwrap();
        let r = all.iter().find(|a| a.key == key).unwrap();
        assert_eq!(r.priority, AlertPriority::Critical, "vencido hace más de 15 días");
        snooze(&conn, &[key.clone()], today + Duration::days(1)).unwrap();
        assert!(!collect_alerts(&conn, &s, today).unwrap().iter().any(|a| a.key == key));
        assert!(collect_alerts(&conn, &s, today + Duration::days(1)).unwrap().iter().any(|a| a.key == key));
        s.disabled_alert_kinds = vec![AlertKind::ReceivableOverdue];
        assert!(!collect_alerts(&conn, &s, today + Duration::days(1)).unwrap().iter().any(|a| a.key == key));
    }

    #[test]
    fn cash_shortfall_is_critical() {
        let conn = crate::db::open(":memory:").unwrap();
        let s = Settings::default(); // caja inicial 0
        finance::add_payable(&conn, &PayableInput {
            supplier_id: None,
            supplier_name: "Arriendo".into(),
            amount: 300_000,
            issue_date: d(2026, 11, 1),
            due_date: d(2026, 11, 25),
            document_type: DocumentType::Factura,
            document_number: None,
            category: Category::Arriendo,
            notes: String::new(),
        })
        .unwrap();
        let alerts = collect_alerts(&conn, &s, d(2026, 11, 19)).unwrap();
        assert!(alerts.iter().any(|a| a.kind == AlertKind::CashShortfall && a.priority == AlertPriority::Critical));
    }
}
