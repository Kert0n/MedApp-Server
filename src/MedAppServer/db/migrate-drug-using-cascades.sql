-- Применить один раз к существующей базе до запуска новой версии сервера.
ALTER TABLE usings DROP CONSTRAINT IF EXISTS usings_drug_fkey;
ALTER TABLE usings
    ADD CONSTRAINT usings_drug_fkey
        FOREIGN KEY (drug_id) REFERENCES user_drugs (id) ON DELETE CASCADE;

ALTER TABLE user_drugs DROP CONSTRAINT IF EXISTS user_drugs_med_kit_fkey;
ALTER TABLE user_drugs
    ADD CONSTRAINT user_drugs_med_kit_fkey
        FOREIGN KEY (med_kit_id) REFERENCES med_kits (id) ON DELETE CASCADE;

ALTER TABLE user_med_kits DROP CONSTRAINT IF EXISTS user_med_kits_med_kit_fkey;
ALTER TABLE user_med_kits
    ADD CONSTRAINT user_med_kits_med_kit_fkey
        FOREIGN KEY (med_kit_id) REFERENCES med_kits (id) ON DELETE CASCADE;
