-- Registra il tenant S/4HANA Cloud Public di sviluppo come sistema di origine (D23).
-- Eseguire come ewf_owner sul database di lavoro "ewf" (non su ewf_test).
-- connection_ref = NAME delle variabili d'ambiente EWF_S4_<NAME>_URL / _USER / _PASSWD (in setenv.bat).
INSERT INTO ewf_core.backend_system (code, description, kind, connection_ref, active)
VALUES ('S4DEV', 'S/4HANA Cloud Public - sviluppo', 'S4_PUBLIC', 'S4DEV', true)
ON CONFLICT (code) DO UPDATE SET kind = EXCLUDED.kind, connection_ref = EXCLUDED.connection_ref, active = true;

-- Per spegnere il collegamento senza toccare la configurazione:
-- UPDATE ewf_core.backend_system SET active = false WHERE code = 'S4DEV';
