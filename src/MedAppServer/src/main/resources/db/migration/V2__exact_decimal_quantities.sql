DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM user_drugs
        WHERE quantity < 0
           OR quantity::text IN ('NaN', 'Infinity', '-Infinity')
           OR abs(quantity) >= 10000000000000
    ) THEN
        RAISE EXCEPTION 'user_drugs.quantity contains negative, non-finite, or NUMERIC(19,6)-overflow values';
    END IF;

    IF EXISTS (
        SELECT 1 FROM usings
        WHERE planned_amount < 0
           OR planned_amount::text IN ('NaN', 'Infinity', '-Infinity')
           OR abs(planned_amount) >= 10000000000000
    ) THEN
        RAISE EXCEPTION 'usings.planned_amount contains negative, non-finite, or NUMERIC(19,6)-overflow values';
    END IF;
END $$;

ALTER TABLE user_drugs
    ALTER COLUMN quantity TYPE NUMERIC(19,6)
    USING round(quantity::numeric, 6);

ALTER TABLE usings
    ALTER COLUMN planned_amount TYPE NUMERIC(19,6)
    USING round(planned_amount::numeric, 6);

ALTER TABLE user_drugs
    ADD CONSTRAINT ck_user_drugs_quantity_nonnegative CHECK (quantity >= 0);

ALTER TABLE usings
    ADD CONSTRAINT ck_usings_planned_amount_nonnegative CHECK (planned_amount >= 0);
