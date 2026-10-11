-- Istantanee dei valori durante la guida (una ogni ~15 s). `ts` = millisecondi epoch dal telefono.
CREATE TABLE IF NOT EXISTS snapshots (
  ts INTEGER PRIMARY KEY,
  rpm REAL, speed REAL, coolant REAL, load REAL, map REAL, iat REAL, throttle REAL,
  battery REAL, lph REAL, trip_km REAL,
  values_json TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS trips (
  start_ms INTEGER PRIMARY KEY,
  end_ms INTEGER NOT NULL,
  km REAL NOT NULL,
  fuel_l REAL NOT NULL,
  max_speed REAL NOT NULL
);

-- Un codice errore visto per la prima volta a first_seen_ms. cleared_ms NULL = ancora presente.
CREATE TABLE IF NOT EXISTS dtc (
  code TEXT NOT NULL,
  first_seen_ms INTEGER NOT NULL,
  kind TEXT NOT NULL,
  last_seen_ms INTEGER NOT NULL,
  cleared_ms INTEGER,
  freeze TEXT,
  PRIMARY KEY (code, first_seen_ms)
);

CREATE TABLE IF NOT EXISTS drive_reports (
  start_ms INTEGER PRIMARY KEY,
  end_ms INTEGER NOT NULL,
  text TEXT NOT NULL
);

-- Valori singoli: 'status' (ultimo stato dell'app), 'last_ingest'
CREATE TABLE IF NOT EXISTS meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);
