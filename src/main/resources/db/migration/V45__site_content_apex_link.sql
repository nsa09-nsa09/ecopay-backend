ALTER TABLE site_content ADD COLUMN apex_link VARCHAR(512);
UPDATE site_content SET apex_link = 'https://apex-digital.kz' WHERE id = 1 AND apex_link IS NULL;
