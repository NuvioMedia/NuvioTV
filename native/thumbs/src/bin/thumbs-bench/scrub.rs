use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use aiostreams_container::Options;
use aiostreams_thumbs::{Session, Settings};

/// A held seek key: a new position every `interval_ms`, thumbnails made by a [`Session`].
pub fn run(pos: &[String], steps: usize, interval_ms: u64, workers: usize) -> Result<(), String> {
    let url = pos.get(1).ok_or("url")?;
    let asked: Arc<Mutex<HashMap<u64, Instant>>> = Arc::default();
    let shown: Arc<Mutex<Vec<(u64, f64)>>> = Arc::default();
    let t0 = Instant::now();
    let session = {
        let (asked, shown) = (asked.clone(), shown.clone());
        Session::open(
            url,
            Options::default(),
            Settings {
                workers,
                ..Settings::default()
            },
            move |t| {
                if let Some(at) = asked.lock().unwrap().get(&t.time_ms) {
                    shown
                        .lock()
                        .unwrap()
                        .push((t.time_ms, at.elapsed().as_secs_f64() * 1e3));
                }
            },
        )
        .map_err(|e| e.to_string())?
    };
    let open_ms = t0.elapsed().as_secs_f64() * 1e3;
    let k = session.keyframes();
    let mut last = 0;
    for i in 0..steps {
        let ms = k.duration_ms / 10 + k.duration_ms / 2 * i as u64 / (steps.max(2) - 1) as u64;
        last = k.entries[k.at(ms)].time_ms;
        asked
            .lock()
            .unwrap()
            .entry(last)
            .or_insert_with(Instant::now);
        session.want(ms, None);
        std::thread::sleep(Duration::from_millis(interval_ms));
    }
    let deadline = Instant::now() + Duration::from_secs(20);
    while Instant::now() < deadline && !shown.lock().unwrap().iter().any(|s| s.0 == last) {
        std::thread::sleep(Duration::from_millis(5));
    }
    let settled = asked.lock().unwrap()[&last].elapsed().as_secs_f64() * 1e3;
    let t = Instant::now();
    let cached = (0..steps)
        .filter(|&i| {
            session
                .want(
                    k.duration_ms / 10 + k.duration_ms / 2 * i as u64 / (steps.max(2) - 1) as u64,
                    None,
                )
                .is_some()
        })
        .count();
    let cached_us = t.elapsed().as_secs_f64() * 1e6 / steps as f64;
    let rss_busy = crate::metrics::rss_mb().0;
    std::thread::sleep(Duration::from_secs(12));
    let rss_idle = crate::metrics::rss_mb().0;
    eprintln!(
        "cached {cached}/{steps} at {cached_us:.1} us each; rss {rss_busy:.0} MB busy, {rss_idle:.0} MB after 12 s idle"
    );
    let shown = shown.lock().unwrap();
    let mut latency: Vec<f64> = shown.iter().map(|s| s.1).collect();
    latency.sort_by(|a, b| a.partial_cmp(b).unwrap());
    println!(
        "{{\"open_ms\":{open_ms:.0},\"asked\":{steps},\"shown\":{},\"request_to_image_med\":{:.0},\"settled_ms\":{settled:.0}}}",
        shown.len(),
        latency.get(latency.len() / 2).copied().unwrap_or(0.0)
    );
    Ok(())
}
