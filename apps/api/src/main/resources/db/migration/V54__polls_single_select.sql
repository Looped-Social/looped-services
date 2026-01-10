-- Force polls to be single-select (max_selections = 1)

UPDATE polls SET max_selections = 1 WHERE max_selections <> 1;

ALTER TABLE polls DROP CONSTRAINT IF EXISTS polls_max_selections_chk;
ALTER TABLE polls ADD CONSTRAINT polls_max_selections_chk CHECK (max_selections = 1);

