use std::sync::mpsc::{Receiver, Sender, channel};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

use crate::{decode, hevc, priority, scale::Accum};

struct Job {
    packet: Vec<u8>,
    y: usize,
    rows: usize,
    acc: Arc<Mutex<Accum>>,
    done: Sender<Result<(), String>>,
}

pub struct Pool {
    tx: Option<Sender<Job>>,
    workers: Vec<JoinHandle<()>>,
}

impl Pool {
    pub fn new(n: usize, decoder: &'static str, idle: bool) -> Pool {
        let (tx, rx) = channel::<Job>();
        let rx = Arc::new(Mutex::new(rx));
        let workers = (0..n)
            .map(|_| {
                let rx: Arc<Mutex<Receiver<Job>>> = rx.clone();
                crate::spawn("thumbs-strip", move || {
                    if idle {
                        priority::background();
                    }
                    let mut dec: Option<decode::Decoder> = None;
                    loop {
                        let Job {
                            packet,
                            y,
                            rows,
                            acc,
                            done,
                        } = match rx.lock().unwrap().recv() {
                            Ok(j) => j,
                            Err(_) => return,
                        };
                        let opts = decode::Options {
                            threads: 1,
                            skip_loop_filter: true,
                            fast: false,
                        };
                        let r = (|| {
                            if dec.is_none() {
                                dec = Some(decode::Decoder::open(decoder, &opts)?);
                            }
                            let f = dec.as_mut().unwrap().decode(&packet)?;
                            acc.lock().unwrap().add(f, y, rows)
                        })();
                        // The caller takes the accumulator back once every strip has answered.
                        drop(acc);
                        done.send(r).ok();
                    }
                })
            })
            .collect();
        Pool {
            tx: Some(tx),
            workers,
        }
    }

    pub fn run(
        &self,
        split: hevc::Split,
        ow: usize,
        oh: usize,
        samples: usize,
    ) -> Result<Accum, String> {
        let acc = Arc::new(Mutex::new(Accum::new(split.area, ow, oh, samples)));
        let (done, results) = channel();
        let n = split.pieces.len();
        for p in split.pieces {
            self.tx
                .as_ref()
                .unwrap()
                .send(Job {
                    packet: p.packet,
                    y: p.y as usize,
                    rows: p.rows as usize,
                    acc: acc.clone(),
                    done: done.clone(),
                })
                .unwrap();
        }
        for _ in 0..n {
            results.recv().map_err(|e| e.to_string())??;
        }
        Ok(Arc::try_unwrap(acc).ok().unwrap().into_inner().unwrap())
    }
}

impl Drop for Pool {
    fn drop(&mut self) {
        self.tx.take();
        for w in self.workers.drain(..) {
            w.join().ok();
        }
    }
}
