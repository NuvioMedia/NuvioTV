use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::{Arc, Condvar, Mutex};

use aiostreams_container::{Keyframes, Options};

use crate::{Result, Settings, Source, Thumbnail, priority};

struct State {
    /// Requests so far; the wanted keyframe carries the count at its request.
    asked: u64,
    wanted: Option<(usize, u64)>,
    /// Keyframes being fetched or rendered.
    busy: HashSet<usize>,
    /// Keyframes that failed since the pointer last moved to another.
    failed: HashSet<usize>,
    /// Whether the last fetch or render failed, so a run of failures is logged once.
    failing: bool,
    /// Packets fetched and not yet rendered, with the request each answers.
    fetched: Vec<(u64, usize, Vec<u8>)>,
    /// The request the last thumbnail passed on answered.
    shown: u64,
    cache: HashMap<usize, Arc<Thumbnail>>,
    order: VecDeque<usize>,
    closed: bool,
}

struct Shared {
    state: Mutex<State>,
    wake: Condvar,
}

/// Thumbnails for a seek bar: the newest position asked for wins, several keyframes are fetched
/// at once, and only the newest fetched renders.
pub struct Session {
    shared: Arc<Shared>,
    keyframes: Arc<Keyframes>,
}

impl Session {
    /// `ready` runs on a background thread with each thumbnail made.
    pub fn open(
        url: &str,
        options: Options,
        settings: Settings,
        ready: impl Fn(Arc<Thumbnail>) + Send + 'static,
    ) -> Result<Session> {
        let source = Source::open(url, options)?;
        let keyframes = source.keyframes().clone();
        let (keep, capacity) = (settings.keep_decoders, settings.cache);
        let mut renderer = source.renderer(settings.clone());
        let shared = Arc::new(Shared {
            state: Mutex::new(State {
                asked: 0,
                wanted: None,
                busy: HashSet::new(),
                failed: HashSet::new(),
                failing: false,
                fetched: Vec::new(),
                shown: 0,
                cache: HashMap::new(),
                order: VecDeque::new(),
                closed: false,
            }),
            wake: Condvar::new(),
        });
        let mut sources: Vec<Source> = (1..settings.fetches).map(|_| source.fork()).collect();
        sources.push(source);
        for mut source in sources {
            let shared = shared.clone();
            crate::spawn("thumbs-fetch", move || {
                loop {
                    let (index, asked) = {
                        let mut s = shared.state.lock().unwrap();
                        loop {
                            if s.closed {
                                return;
                            }
                            match s.wanted {
                                Some((i, asked))
                                    if !s.cache.contains_key(&i)
                                        && !s.busy.contains(&i)
                                        && !s.failed.contains(&i) =>
                                {
                                    s.busy.insert(i);
                                    break (i, asked);
                                }
                                _ => s = shared.wake.wait(s).unwrap(),
                            }
                        }
                    };
                    let packet = source.packet(index);
                    let mut s = shared.state.lock().unwrap();
                    match packet {
                        Ok(packet) => s.fetched.push((asked, index, packet)),
                        Err(e) => {
                            s.busy.remove(&index);
                            s.failed.insert(index);
                            if !std::mem::replace(&mut s.failing, true) {
                                let ms = source.keyframes().entries[index].time_ms;
                                log::warn!(target: "thumbnails", "reading the keyframe at {ms} ms failed: {e}");
                            }
                        }
                    }
                    drop(s);
                    shared.wake.notify_all();
                }
            });
        }
        {
            let (shared, keyframes) = (shared.clone(), keyframes.clone());
            crate::spawn("thumbs-render", move || {
                priority::background();
                loop {
                    let (asked, index, packet) = {
                        let mut s = shared.state.lock().unwrap();
                        loop {
                            if s.closed {
                                return;
                            }
                            // The wanted keyframe first, even when an older request fetched it.
                            let wanted = s.wanted.map(|w| w.0);
                            let pick = (0..s.fetched.len())
                                .find(|&k| Some(s.fetched[k].1) == wanted)
                                .or_else(|| (0..s.fetched.len()).max_by_key(|&k| s.fetched[k].0));
                            if let Some(k) = pick {
                                let next = s.fetched.swap_remove(k);
                                // Rendering them would only fill the cache, which costs a slow
                                // device more than fetching again.
                                for (_, stale, _) in std::mem::take(&mut s.fetched) {
                                    s.busy.remove(&stale);
                                }
                                break next;
                            }
                            let (next, idle) = shared.wake.wait_timeout(s, keep).unwrap();
                            s = next;
                            if idle.timed_out() && s.fetched.is_empty() {
                                renderer.release();
                            }
                        }
                    };
                    let jpeg = renderer.render(&packet);
                    let mut s = shared.state.lock().unwrap();
                    s.busy.remove(&index);
                    let jpeg = match jpeg {
                        Ok(jpeg) => jpeg,
                        Err(e) => {
                            s.failed.insert(index);
                            if !std::mem::replace(&mut s.failing, true) {
                                let ms = keyframes.entries[index].time_ms;
                                log::warn!(target: "thumbnails", "decoding the keyframe at {ms} ms failed: {e}");
                            }
                            continue;
                        }
                    };
                    s.failing = false;
                    let thumbnail = Arc::new(Thumbnail {
                        time_ms: keyframes.entries[index].time_ms,
                        width: renderer.width(),
                        height: renderer.height(),
                        jpeg,
                    });
                    if s.cache.insert(index, thumbnail.clone()).is_none() {
                        s.order.push_back(index);
                        while s.order.len() > capacity {
                            let old = s.order.pop_front().unwrap();
                            s.cache.remove(&old);
                        }
                    }
                    let show = asked > s.shown || s.wanted.is_some_and(|w| w.0 == index);
                    if show {
                        s.shown = s.shown.max(asked);
                    }
                    drop(s);
                    shared.wake.notify_all();
                    if show {
                        ready(thumbnail);
                    }
                }
            });
        }
        Ok(Session { shared, keyframes })
    }

    pub fn keyframes(&self) -> &Keyframes {
        &self.keyframes
    }

    /// The thumbnail for `ms` if it was made before and isn't `showing`, the keyframe time of the
    /// one on screen; otherwise it is made and passed to `ready`.
    pub fn want(&self, ms: u64, showing: Option<u64>) -> Option<Arc<Thumbnail>> {
        let index = self.keyframes.at(ms);
        let mut s = self.shared.state.lock().unwrap();
        s.asked += 1;
        let asked = s.asked;
        if s.wanted.is_none_or(|w| w.0 != index) {
            s.failed.clear();
        }
        s.wanted = Some((index, asked));
        if let Some(t) = s.cache.get(&index).cloned() {
            s.shown = asked;
            return (Some(t.time_ms) != showing).then_some(t);
        }
        drop(s);
        self.shared.wake.notify_all();
        None
    }
}

/// The threads stop after the read or render they are in, without holding up the caller.
impl Drop for Session {
    fn drop(&mut self) {
        self.shared.state.lock().unwrap().closed = true;
        self.shared.wake.notify_all();
    }
}
