-- Dati nati col simulatore o con la riproduzione di un log: conservati ma marcati, e ignorati dagli strumenti per Claude.
ALTER TABLE snapshots ADD COLUMN simulated INTEGER NOT NULL DEFAULT 0;
ALTER TABLE trips ADD COLUMN simulated INTEGER NOT NULL DEFAULT 0;
ALTER TABLE dtc ADD COLUMN simulated INTEGER NOT NULL DEFAULT 0;
ALTER TABLE drive_reports ADD COLUMN simulated INTEGER NOT NULL DEFAULT 0;
